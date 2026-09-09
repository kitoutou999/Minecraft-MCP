# Minecraft MCP Bridge (mcbridge)

Pont entre Claude (ou tout client MCP) et un **client Minecraft** : screenshots, camera, isolation
d'entites au rendu, options client, rechargement du pack, lecture des logs, reflexion Java.

Le but est de laisser une IA **verifier visuellement** ce qu'elle a developpe sur un serveur
(modeles ModelEngine, meubles Nexo, HUD, shaders) sans intervention humaine : se placer, cadrer,
capturer, corriger.

Le mod ne pilote que le client. Le serveur de jeu se pilote par commandes envoyees en tant que
joueur (`send_command`) ou par RCON en dehors de ce projet.

```
Claude Code / client MCP
        |  MCP (stdio)
  mcp-server  (Node 20, TypeScript)          mcp-server/
        |  HTTP  POST /rpc  + GET /events (SSE), jeton bearer, 127.0.0.1 uniquement
  mod Fabric "mcbridge"  (client Minecraft)   mod/
        |  tout acces au jeu passe par le thread de rendu
  info  player  camera  vision  world  chat  events  client  resources  logs  game  focus  reflect
```

## Prerequis

| Outil | Version |
|---|---|
| Minecraft | 26.1.2 avec Fabric Loader 0.19.3+ et Fabric API |
| JDK | 25 (Minecraft 26.x est compile en Java 25) |
| Node.js | 20 ou plus recent |
| Gradle | wrapper fourni (9.5.1), rien a installer |

## Installation

```bash
# 1. Mod client
cd mod
./gradlew build            # -> build/libs/mcbridge-0.1.0.jar
./gradlew installMod       # copie le jar dans ~/.minecraft/mods (-PmodsDir=... pour un autre dossier)

# 2. Serveur MCP
cd ../mcp-server
npm ci
npm run build              # -> dist/index.js
npm run smoke              # verifie l'enregistrement des tools sans Minecraft

# 3. Lancer Minecraft une fois : le mod cree ~/.minecraft/config/mcbridge.json avec un jeton.
#    Le log affiche : [mcbridge] pret : bridge http://127.0.0.1:25580 (30 methodes), jeton dans ...

# 4. Enregistrer le serveur MCP dans Claude Code (le jeton est lu directement dans la config du mod)
claude mcp add mcbridge -e MCBRIDGE_CONFIG=$HOME/.minecraft/config/mcbridge.json -- node $(pwd)/dist/index.js
```

Ou via `.mcp.json` a la racine d'un projet, voir `examples/mcp.json`.

### Avec Modrinth App (ou Prism)

Chaque profil a son propre dossier de jeu : le jar va dans `<profil>/mods/` et le mod ecrit sa
config dans `<profil>/config/mcbridge.json`. Le serveur MCP et les scripts cherchent ce fichier
automatiquement dans `~/.minecraft`, les profils Modrinth App (installation classique ou snap
`~/snap/modrinth/common/...`, Windows, macOS) et les instances Prism ; en cas de doute, donner le
chemin explicitement avec `MCBRIDGE_CONFIG`.

```bash
# exemple : profil "Clicker" de Modrinth App installe en snap
PROFIL=~/snap/modrinth/common/.local/share/ModrinthApp/profiles/Clicker
MCBRIDGE_MODS_DIR=$PROFIL/mods ./gradlew installMod      # depuis mod/
claude mcp add mcbridge -e MCBRIDGE_CONFIG=$PROFIL/config/mcbridge.json -- node /chemin/vers/mcp-server/dist/index.js
```

### Client sur une autre machine

Le bridge doit etre joignable depuis la machine qui execute Claude Code. Dans
`config/mcbridge.json` du client : `"host": "0.0.0.0"` et `"allowRemote": true`, ouvrir le port
25580 sur le pare-feu, puis cote MCP : `MCBRIDGE_URL=http://<ip du client>:25580` et
`MCBRIDGE_TOKEN=<jeton copie depuis le fichier>`. Le jeton reste obligatoire.

### Compatibilite avec les autres mods

Teste avec un profil contenant Fabric API, Sodium, Iris, Voxy, Litematica, ModelRecorder. Le
focus d'entites agit avant le rendu et ne depend pas du moteur de terrain. Le masquage du terrain
coupe le point commun a vanilla et Sodium, et un mixin optionnel coupe les LOD de Voxy. Iris sans
shader pack se comporte comme vanilla ; avec un pack actif, ciel et brouillard sont geres par le
pack : desactiver les shaders pour le mode studio. Detail dans `docs/ROADMAP.md`.

## Utilisation

Photographier un modele, depuis Claude :

1. `list_entities` avec `types: ["item_display"]` ou `describe_scene` : trouver la cible.
2. `frame_target` avec son UUID : cadrage, placement, fond uni, capture, restauration, en un appel.

Le meme resultat a la main, quand il faut controler chaque etape :

1. `send_command` `gamemode spectator` puis `send_command` `tp @s 120 75 -40` : se placer.
2. `focus_entities` avec l'UUID de la cible, `focus_scene` pour le fond uni.
3. `look_at` le centre de la cible, puis `screenshot`.
4. `focus_clear`.

Apres une modification du pack : `reload_resource_pack`, puis `get_client_log` avec
`levels: ["ERROR","WARN"]`, puis `compare_all_references` pour savoir ce qui a change visuellement.

Verifier une animation plutot qu'une pose : se placer, `focus_scene` pour isoler le sujet, puis
`capture_animation`, qui renvoie une planche de vues successives.

Surveiller les regressions visuelles :

1. `save_reference` une fois sur chaque element a surveiller, avec un nom parlant, avant de toucher
   au pack. Camera, options de scene et sujet isole sont memorises avec l'image. Isoler le sujet
   (`focus`) et masquer le decor (`scene`) evite qu'un joueur de passage fasse diverger la mesure.
2. Apres une modification, `compare_all_references` : un verdict chiffre par reference, sans image.
3. `compare_reference` sur celles qui ont bouge, pour voir les differences en magenta.

Les images et leurs recettes sont rangees dans `mcbridge-refs/` du dossier de jeu, donc versionnables.

Verifier un objet et son lore :

1. `open_inventory` pour l'inventaire du joueur, ou `send_command` la commande du menu du plugin.
2. `get_gui` : numero de chaque case, objet contenu, position a l'ecran.
3. `get_item_lore` : le texte exact de l'infobulle, ligne par ligne, avec couleurs et styles.
4. `screenshot_gui` avec `crop: "slot"` pour la texture, ou `hoverSlot` et `crop: "tooltip"` pour le rendu du lore.
5. `click_slot` pour changer de page ou entrer dans une categorie, puis reprendre a l'etape 2.

Le clic est la seule action du mod qui modifie l'etat du serveur : il est desactive par defaut
(`enableGuiClicks`), limite au clic simple et au clic rapide, et `dryRun` permet de verifier la
case visee sans rien envoyer.

### Tools disponibles (48 tools, 46 methodes RPC)

| Groupe | Tools |
|---|---|
| Etat | `get_status`, `get_player` |
| Camera | `get_camera`, `look`, `look_at` |
| Vision | `screenshot`, `describe_scene` |
| Monde | `get_block`, `list_entities`, `get_entity` |
| Chat et commandes | `send_command`, `send_chat`, `get_chat`, `poll_events` |
| Client | `get_client_options`, `set_client_options`, `reload_resource_pack`, `get_client_log`, `wait_ticks` |
| Focus | `focus_entities`, `focus_scene`, `focus_region`, `focus_clear`, `focus_status` |
| Studio | `frame_target`, `studio_bounds` |
| Animation | `capture_animation` |
| References visuelles | `save_reference`, `compare_reference`, `compare_all_references`, `list_references`, `delete_reference` |
| Interfaces | `get_gui`, `open_inventory`, `close_gui`, `get_item_lore`, `hover_slot`, `screenshot_gui`, `click_slot` |
| Reflexion | `reflect_invoke`, `reflect_get_field`, `reflect_set_field`, `reflect_new_instance`, `get_class_info`, `var_get`, `var_list`, `var_delete`, `var_clear` |

Reference complete avec les parametres : [docs/TOOLS.md](docs/TOOLS.md).

### Tester sans MCP

`scripts/rpc.sh` appelle directement le bridge (jeton lu dans la config du mod) :

```bash
scripts/rpc.sh info.status
scripts/rpc.sh chat.send '{"message":"/time set noon"}'
scripts/shot.sh /tmp/capture.jpg          # screenshot decode sur disque
```

## Configuration du mod (`~/.minecraft/config/mcbridge.json`)

| Cle | Defaut | Role |
|---|---|---|
| `host`, `port` | `127.0.0.1`, `25580` | Adresse du bridge. Ne pas exposer : controle total du client. |
| `token`, `requireAuth` | genere, `true` | Jeton bearer obligatoire. |
| `allowRemote` | `false` | Refuse toute connexion non locale meme si `host` change. |
| `callTimeoutMs` | `8000` | Attente max du thread de jeu par appel. |
| `commandMinIntervalMs` | `1100` | Intervalle minimal entre deux commandes. Un serveur deconnecte pour spam au-dela d'une dizaine de commandes rapprochees. |
| `preferClientTeleport` | `true` | En spectateur, deplacer la camera sans commande. Evite la quasi-totalite des envois pendant un cadrage ou une comparaison. |
| `teleportCommand`, `gamemodeCommand` | `minecraft:tp`, `minecraft:gamemode` | Commandes vanilla envoyees par le mod. Le prefixe `minecraft:` contourne les plugins qui les redefinissent : EssentialsX rejette `@s` sur `/tp` et impose sa syntaxe sur `/gamemode`. |
| `reloadTimeoutMs` | `90000` | Attente max d'un rechargement de ressources. |
| `enableVision`, `enableCommands`, `enableClientOptions`, `enableFocus`, `enableReflection` | `true` | Portes de capacites. |
| `enableGuiClicks` | `false` | Autorise `click_slot` a envoyer un vrai clic au serveur. Seule capacite qui modifie l'etat du serveur : a n'activer qu'en developpement. |
| `allowedClickTypes` | `pickup`, `quick_move` | Types de clic autorises. Les autres deplacent ou jettent des objets. |
| `screenshot.defaultMaxWidth` | `1280` | Largeur max renvoyee (0 = native). |
| `screenshot.defaultFormat`, `screenshot.jpegQuality` | `jpeg`, `0.85` | Format par defaut. |
| `screenshot.defaultWaitTicks` | `2` | Ticks attendus avant capture (10 apres une teleportation). |
| `reflection.allowedPackages`, `reflection.blockedPackages` | voir fichier | Classes accessibles par reflexion. |

Variables d'environnement du serveur MCP : `MCBRIDGE_URL`, `MCBRIDGE_TOKEN`, `MCBRIDGE_CONFIG`,
`MCBRIDGE_TIMEOUT_MS`, `MCBRIDGE_TRANSPORT` (`stdio` ou `http`), `MCBRIDGE_HTTP_PORT`.

## Securite

- Le bridge n'ecoute que sur l'interface locale et exige un jeton.
- `send_command` agit avec les permissions du joueur connecte : sur un serveur partage, donner
  a ce compte uniquement les permissions necessaires (LuckPerms).
- La reflexion donne acces a tout le client Java. Les packages `java.lang.Runtime`,
  `ProcessBuilder`, `System`, `java.io`, `java.nio.file`, `java.net` sont bloques par defaut.
  Desactiver `enableReflection` si elle n'est pas necessaire.

## Structure du depot

```
mod/          mod Fabric client (Java 25), voir docs/ARCHITECTURE.md
mcp-server/   serveur MCP TypeScript, catalogue dans src/tools.ts
docs/         ARCHITECTURE, PROTOCOL, TOOLS (genere), ROADMAP
scripts/      rpc.sh, shot.sh, gen-tools-doc.mjs
examples/     mcp.json pour Claude Code
reference/    depots analyses (mcpfabric, minecraft-mcp), ignores par git
CLAUDE.md     consignes pour les IA qui font evoluer ce projet
```

## Feuille de route

Le socle (ce depot) couvre la capture, la camera, le focus d'entites et la reflexion. Les lots
suivants sont decrits dans [docs/ROADMAP.md](docs/ROADMAP.md) : masquage du terrain et du ciel,
focus par zone, mode studio (fond uni, cadrage automatique), rendu hors ecran.

## Licence

MIT. Inspire de [mcpfabric](https://github.com/Etoryx/mcpfabric) et de
[minecraft-mcp](https://github.com/InventivetalentDev/minecraft-mcp), tous deux sous MIT, voir `LICENSE`.
