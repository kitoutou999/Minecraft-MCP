# CLAUDE.md : consignes pour faire evoluer Minecraft-MCP

Ce fichier s'adresse aux instances Claude (ou autres IA) qui modifient ce depot. Lire aussi
`docs/ARCHITECTURE.md` avant de toucher au mod, et `docs/ROADMAP.md` pour savoir ou on en est.

## Perimetre et contexte

- Le projet est un **mod client Fabric** (`mod/`) plus un **serveur MCP** (`mcp-server/`). Il ne
  contient volontairement aucun plugin serveur : le serveur Paper se pilote par RCON ou par
  commandes envoyees en tant que joueur (`chat.send`).
- Cible : **Minecraft 26.1.2**, Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, **Java 25**.
  Minecraft 26.x est **non obfusque** : les classes et methodes portent leurs noms Mojang, il n'y a
  pas de mappings ni de remapping dans le build.
- Usage vise : verification visuelle par une IA (screenshots, focus, studio) de contenu developpe
  sur un serveur (ModelEngine, MythicMobs, Nexo, HUD).

## Client de test de l'utilisateur

Profil Modrinth App (snap) : `~/snap/modrinth/common/.local/share/ModrinthApp/profiles/Clicker`
(Fabric 26.1.2, Fabric API 0.155.2, Sodium, Iris, Voxy, Litematica, ModelRecorder). Le jar va dans
`mods/` de ce profil et la config du mod est dans `config/mcbridge.json` du meme profil. Le serveur
Paper de test est dans `../ClickerServer` (26.1.2, RCON actif, voir `../Clicker/CLAUDE.md`).

## Commandes

```bash
cd mod && ./gradlew build                 # compile le mod -> build/libs/mcbridge-<version>.jar
cd mod && ./gradlew test                  # tests unitaires du mod, sans Minecraft (quelques secondes)
cd mod && ./gradlew installMod            # copie dans ~/.minecraft/mods
cd mcp-server && npm ci && npm run build  # compile le serveur MCP
cd mcp-server && npm run verify           # tests + coherence du contrat + smoke, sans Minecraft
node scripts/check-contract.mjs           # methodes Java <-> catalogue MCP (demande dist/ a jour)
node scripts/gen-tools-doc.mjs > docs/TOOLS.md   # regenere la reference des tools depuis tools.ts
scripts/build-plugin.sh                   # reconstruit les artefacts du plugin Claude Code
scripts/rpc.sh <methode> '<params json>'  # appelle le bridge directement (Minecraft lance avec le mod)
```

**Avant de committer**, `cd mod && ./gradlew test` et `cd mcp-server && npm run verify` doivent
passer, et `scripts/build-plugin.sh` doit avoir ete relance si le mod ou le serveur MCP a change :
le plugin embarque un jar et un bundle qui ne se reconstruisent pas tout seuls, et un plugin publie
avec des artefacts en retard n'expose pas les tools qu'on vient d'ajouter.

Les tests ne couvrent que ce qui se calcule hors du jeu (cadrage, images, protocole RCON, filtres,
serialisation des actions, catalogue). Le rendu, les mixins et les captures demandent un vrai
client : `installMod`, relance de Minecraft, puis `scripts/rpc.sh info.status`.

## Verifier une API Minecraft avant de l'utiliser

Le jar client 26.1.2 est dans le cache Loom. Toujours verifier une signature avant d'ecrire un
appel ou un mixin, plutot que de deviner depuis une autre version :

```bash
J=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.1.2/minecraft-clientonly-deobf-26.1.2.jar
C=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.1.2/minecraft-common-deobf-26.1.2.jar
javap -p -cp "$J:$C" net.minecraft.client.Camera | grep -E 'position|Rot'
unzip -l "$J" | awk '{print $4}' | grep -i 'renderer/LevelRenderer'
```

Pour lire le code source complet (decompile par Vineflower), `cd mod && ./gradlew genSources`
produit `mod/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.1.2/*-sources.jar`
(quelques minutes la premiere fois). Ensuite :

```bash
S=$(ls mod/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.1.2/*-sources.jar | head -1)
unzip -p "$S" net/minecraft/client/renderer/GameRenderer.java | grep -n 'extractGui'
```

Renommages connus vers 26.2 : `Minecraft.getMainRenderTarget()` devient
`gameRenderer.mainRenderTarget()`. Dans 26.1.2 : `Camera.position()` (pas `getPosition`),
`ResourceKey.identifier()` (pas `location`), `GameProfile.name()`,
`StateHolder.getValues()` renvoie un `Stream<Property.Value>`.

## Regles de code

- **Thread** : tout acces a l'etat du jeu (joueur, monde, options, rendu) se fait sur le thread de
  rendu via `ClientMc.call(() -> ...)`. Les handlers sont appeles depuis des threads HTTP. Ne
  jamais bloquer le thread de rendu en attendant un future : recuperer le future sur le thread de
  rendu, l'attendre avec `MainThread.await` cote HTTP (voir `ResourceHandlers`).
- **Exclusivite** : une methode qui prend le controle du client (camera, mode de jeu, focus, options
  de rendu, interface) s'enregistre avec `router.registerExclusive`, jamais `register`. Le routeur
  les serialise ; deux en parallele se marcheraient dessus et la restauration de la premiere
  effacerait le reglage de la seconde. Une lecture garde `register` pour rester disponible pendant
  une longue prise de vue. Le tool correspondant ne doit alors pas etre annonce `readOnlyHint` :
  utiliser `CONTROLS_CLIENT` dans `tools.ts` (`check-contract.mjs` le verifie).
- **Sortie du client** : tout handler qui deplace la camera, passe en spectateur, force le champ de
  vision ou modifie le focus passe par `util/Excursion` en try-with-resources, plutot que de
  reecrire la sequence releve / spectateur / restauration. Le mode de jeu n'est rendu que si la
  position l'a ete : rendre la survie en plein vol fait tomber le joueur.
- **Erreurs** : lever `RpcException` avec un code de `RpcException` (`bad_request`, `no_player`,
  `unavailable`, `forbidden`, `not_found`, `timeout`, `busy`, `internal`). Messages en francais,
  precis, avec ce qu'il faut faire pour s'en sortir.
- **Pas de dependance externe** dans le mod : JDK (`HttpServer`, `ImageIO`), Gson (fourni par
  Minecraft), Fabric API. Pas de Java-WebSocket, pas de Netty direct.
- **Nommage** : methodes RPC `espace.action` en camelCase (`vision.screenshot`), tools MCP en
  snake_case (`screenshot`, `focus_entities`). Un tool = un handler, sauf un tool compose cote
  serveur MCP (`local: true` dans `tools.ts`, comme `run_steps`) : pas de methode Java, un handler
  dans `index.ts`.
- **Cout en tokens** : le texte d'un resultat est relu par le modele a chaque tour suivant.
  `mcp-server/src/results.ts` serialise en JSON compact avec les flottants arrondis : ne pas
  reintroduire d'indentation, ne jamais repeter une image en texte, renvoyer par defaut le minimum
  utile et le detail sur demande. Deux appels dependants se font en un `run_steps` : un tour de
  moins vaut plus qu'un resultat plus court (voir `docs/ARCHITECTURE.md`, Cout en tokens).
- **Config** : toute nouvelle capacite sensible a sa porte `enableXxx` dans `BridgeConfig`,
  verifiee dans le handler, exposee dans `info.status`.
- **Commentaires et docs en francais**, identifiants en anglais. Pas de caractere tiret long.
- **Calcul pur = test** : une geometrie, un format binaire, un filtre ou un traitement d'image se
  place dans une classe sans dependance au jeu (`Framing`, `RconCodec`, `CommandGuard`,
  `PackageFilter`, `Images`) et recoit un test dans `mod/src/test/java`. C'est la ou une erreur ne
  se verrait autrement qu'a l'oeil, sur une image, apres coup.
- **Rien d'irreversible cote client** sans restauration : un handler qui change une option
  (ex. `hideGui`) la restaure ou renvoie l'etat precedent.

## Ajouter un tool (checklist)

1. Handler Java dans `mod/src/main/java/fr/tomda/mcbridge/handlers/` (ou le package concerne),
   enregistre dans `McBridgeMod.onInitializeClient` via `XxxHandlers.register(router, ...)`.
2. Verifier les signatures Minecraft utilisees avec `javap` (section ci-dessus).
3. `cd mod && ./gradlew build` doit passer.
4. Entree dans `mcp-server/src/tools.ts` : `name`, `method`, `description` precise (prerequis,
   effets, unites), `inputSchema` zod avec `.describe()` sur **chaque** parametre, `annotations`
   (`READ`, `WRITE`, ou `CONTROLS_CLIENT` si la methode est exclusive), `kind: "image"` si le
   resultat contient `base64`. Un tool sans methode Java, compose cote serveur, porte `local: true`
   et recoit son handler dans `index.ts`.
5. `npm run verify` (tests, contrat, smoke), puis regenerer `docs/TOOLS.md`.
6. Mettre a jour `docs/PROTOCOL.md` (table des methodes) et `docs/ROADMAP.md` si le tool cloture un
   item, puis relancer `scripts/build-plugin.sh`.

## Ajouter un mixin (checklist)

1. Classe dans `fr.tomda.mcbridge.mixin`, declaree dans `mcbridge.mixins.json`, nom de methode
   prefixe `mcbridge$`. Un mixin ne porte pas de logique : il consulte un etat (`FocusState`,
   `CaptureState`) que les handlers pilotent.
2. Signature de la cible verifiee par `javap` : la methode Java generique `<E extends Entity>`
   s'ecrit avec `Entity` dans le mixin (effacement de type).
3. Ajouter la classe dans la liste `client` de `mod/src/main/resources/mcbridge.mixins.json`.
4. Un mixin ne fait que consulter un etat (`FocusState`, `CaptureState`) : la logique reste
   testable hors mixin.
5. Si la methode ciblee est aussi injectee par Sodium, Iris ou Voxy (verifier avec
   `javap -v` sur leur jar dans le dossier mods du profil, filtrer `method=`), mettre
   `priority = 2000` pour etre applique apres eux.
6. Pour une classe d'un mod optionnel : `@Pseudo`, `@Mixin(targets = "nom.complet.Classe", remap = false)`,
   `require = 0` sur chaque injecteur, handler sans les parametres de la cible si l'un d'eux est un
   type du mod (un handler `(CallbackInfo ci)` seul est accepte par Mixin). Sous-package `mixin.compat`.

## Pieges connus

- Ne pas deplacer le joueur avec `player.setPos` cote client : le serveur annule. Utiliser une
  commande de teleportation (parametre `teleport` de `vision.screenshot`, `studio.frameTarget`, ou
  `chat.send`).
- **Toujours qualifier les commandes vanilla que le mod envoie lui-meme** : un serveur avec
  EssentialsX reprend `/tp`, `/gamemode`, `/time`, `/weather` et bien d'autres, avec une syntaxe
  differente et sans les selecteurs (`/tp @s x y z` repond "Joueur introuvable"). D'ou
  `teleportCommand` et `gamemodeCommand` en `minecraft:...` dans la config, et l'option `vanilla`
  de `chat.send`. Toute nouvelle commande vanilla envoyee par le mod doit suivre la meme regle.
  Le client ne voit pas l'echec d'une commande : verifier l'effet obtenu (position reelle du
  joueur, mode de jeu courant) plutot que supposer que la commande a abouti.
- `/tp` place les **pieds** du joueur ; la camera est a hauteur des yeux (1,62 bloc debout). Toute
  position de camera calculee doit soustraire `player.getEyeHeight()` avant la teleportation.
- **Deplacer un joueur en survie vers une position en l'air le fait tomber**, avec les degats. Tout
  outil qui deplace le joueur passe donc par `util/Spectator.enter()` avant, et ne rend le mode
  precedent que s'il le ramene au sol. Le vol en creatif ne convient pas : le joueur resterait
  visible des autres et garderait ses collisions, donc une camera dans un mur ne marcherait pas.
- `Screenshot.takeScreenshot` lit la frame precedente : attendre au moins un tick apres avoir
  change `hideGui` ou l'orientation (fait par `VisionHandlers`).
- F1 (`hideGui`) ne masque pas un ecran ouvert, et l'ecran de chat dessine lui-meme l'historique.
  Depuis 26.x l'interface est extraite avant rendu : `ScreenMixin` annule
  `Screen.extractRenderStateWithTooltipAndSubtitles` quand `CaptureState.hideScreens` est vrai.
  Ne jamais fermer l'ecran a la place : cela rend la souris au jeu et ferme definitivement un
  inventaire ou un menu serveur.
- Une entite ModelEngine est une entite de base invisible plus des `item_display` ; un meuble Nexo
  est un `item_display` plus une `interaction`. Le focus doit inclure les displays proches
  (`attachRadius`), sinon le modele disparait.
- `hideGui` (F1) ne masque **pas** l'ecran ouvert : les deux sont extraits separement par
  `GameRenderer.extractGui`. Une capture d'interface met donc `hideHud` a vrai et `hideScreen` a faux.
- Les coordonnees d'interface sont en unites mises a l'echelle ; le framebuffer est en pixels
  physiques. Tout decoupage doit multiplier par `Window.getGuiScale()`.
- `gui.click` envoie un vrai clic au serveur : c'est la seule capacite du mod qui sort de
  l'observation. Le numero transmis est `Slot.index` (place dans le menu, affectee par `addSlot`),
  pas `Slot.getContainerSlot()`. Toute nouvelle action de ce genre doit avoir sa porte de config
  fermee par defaut et un mode `dryRun`.
- Une comparaison d'images n'a de sens que si la prise de vue est reproductible : toute reference
  stocke sa recette (camera, champ de vision, scene, resolution) a cote de l'image.
- **Le serveur compte les commandes comme du spam** : chaque envoi ajoute 20 a un compteur qui ne
  retombe que d'une unite par tick, et 200 deconnecte le joueur ("Kicked for spamming"). Une rafale
  de dix commandes suffit. Tout envoi passe donc par `util/Commands.send`, qui espace les commandes
  de `commandMinIntervalMs`. Ne jamais appeler `connection.sendCommand` directement.
- **Preferer `Commands.moveCamera` a une commande de teleportation** : en spectateur, le serveur
  accepte la position annoncee par le client, donc le deplacement ne coute aucune commande ; la
  commande n'intervient qu'en repli, si la position obtenue ne correspond pas.
- Masquer le terrain ne masque pas les **block entities** (panneaux, coffres, bannieres) : elles ont
  leur propre passe d'extraction et leur propre option.
- `logs/latest.log` peut faire plusieurs Mo : `logs.client` lit tout le fichier, garder `lines`
  raisonnable.
- **RCON est facultatif et non chiffre** : mot de passe et commandes circulent en clair, donc
  liaison locale ou tunnel. Il execute en tant que console, ce qui evite le compteur anti-spam et
  rend la sortie des commandes, mais la console n'a pas de « soi » : pas de selecteur `@s`, il faut
  nommer le joueur.
- **La console n'a pas non plus de dimension** : elle execute depuis l'overworld. Une teleportation
  RCON ecrite `tp <joueur> x y z` sort donc le joueur d'un monde personnalise et le depose aux
  memes coordonnees dans l'overworld, sans erreur ni message. Toute commande console qui depend du
  lieu passe par `minecraft:execute in <dimension> run ...`, comme le fait `util/CommandLines`.
  Constate en jeu le 2026-09-10 sur le monde `clicker_spawn`.
- **Le prefixe de namespace vaut aussi derriere un `execute`** : `execute in <dim> run tp ...` est
  repris par EssentialsX, qui repond « Teleportation en cours » et applique son propre delai. C'est
  `run minecraft:tp` qu'il faut ecrire. `CommandGuard` refuse les commandes de `blockedCommands` y compris derriere un
  `execute ... run` ; c'est un garde-fou contre l'accident, pas contre l'intention.
- **Les artefacts du plugin sont dans le depot** (`plugins/mcbridge/mod/*.jar` et
  `server/mcbridge-mcp.mjs`) : un plugin Claude Code est copie tel quel, sans build. Ils ne se
  regenerent pas seuls, d'ou `scripts/build-plugin.sh` avant tout commit qui touche au mod ou au
  serveur MCP.

## References

`reference/` contient les deux depots analyses (ignores par git). S'il est vide :
`git clone https://github.com/Etoryx/mcpfabric reference/mcpfabric` et
`git clone https://github.com/InventivetalentDev/minecraft-mcp reference/minecraft-mcp`.
Ce qui en a ete repris est documente dans `LICENSE` et `docs/ARCHITECTURE.md`.
