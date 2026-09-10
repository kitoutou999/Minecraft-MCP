# Feuille de route

Chaque lot liste les points d'accroche verifies dans le jar client 26.1.2 (par `javap`), pour
que l'implementation ne parte pas d'une supposition.

## Lot 1 : socle (fait)

Bridge HTTP, catalogue MCP, screenshot avec teleportation et attente, camera, lecture du monde,
chat et commandes, evenements, options client, rechargement des ressources, logs client, focus
d'entites par mixin, reflexion. 31 tools.

Valide en jeu le 2026-09-09 sur le profil Modrinth (Sodium, Iris, Voxy actifs) connecte au serveur
Paper 26.1.2 : bridge, captures avec HUD masque, ecran ouvert (chat) masque sans fermeture, focus
par mixin (PNJ, joueurs, hologrammes et displays masques, cible conservee), reflexion chainee,
logs, evenements, chat, options.

Enseignements du test :
- Un ecran ouvert (chat, menu Echap, inventaire) se dessine par-dessus le HUD masque, et les
  developpeurs en ouvrent un pour liberer la souris : `ScreenMixin` masque l'ecran sans le fermer
  pendant la capture (`hideScreen`, actif par defaut) ; la reponse indique `screenOpen` et
  `screenHidden`.
- La boite englobante d'un `item_display` est un point (min = max) : pour cadrer un meuble Nexo ou
  un modele ModelEngine, `frame_target` devra s'appuyer sur la hitbox de l'`interaction` associee
  ou sur la transformation et l'echelle du display, pas sur `getBoundingBox()`.
- Les particules (feuilles, effets) restent visibles en focus : a traiter au lot 2.
- Reste a mesurer : temps de `reloadResourcePacks` avec le pack Nexo complet.

## Lot 2 : focus complet (valide en jeu le 2026-09-09)

Realise le 2026-09-09 a partir des sources decompilees et des jars de Sodium 0.9.1, Iris 1.11.3 et
Voxy 0.2.18 presents dans le profil de test. Tous les mixins lisent `FocusState` et ne portent aucune
logique.

| Element | Point d'accroche (26.1.2) | Mixin |
|---|---|---|
| Terrain (vanilla et Sodium) | `ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)`, ou Sodium s'injecte pour dessiner ses sections | `ChunkSectionsToRenderMixin` (priorite 2000) |
| Terrain lointain Voxy | `VoxyRenderSystem.renderOpaque` | `compat.VoxyRenderSystemMixin` (`@Pseudo`) |
| Ciel, nuages, meteo | `LevelRenderer.addSkyPass`, `addCloudsPass`, `addWeatherPass` | `LevelRendererMixin` (priorite 2000) |
| Block entities | `LevelRenderer.extractVisibleBlockEntities` (Sodium s'y injecte aussi) | `LevelRendererMixin` |
| Particules | `ParticleEngine.extract(ParticlesRenderState, Frustum, Camera, float)` | `ParticleEngineMixin` |
| Couleur de fond | argument `Vector4f fogColor` de `LevelRenderer.renderLevel`, utilise pour effacer l'ecran | `LevelRendererMixin` (`@ModifyVariable`) |
| Brouillard | `FogRenderer.updateBuffer(FogData)` : debut et fin repousses a l'infini, couleur alignee sur le fond | `FogRendererMixin` (priorite 2000) |
| Camera dans un bloc | `ScreenEffectRenderer.getViewBlockingState(Player)` renvoie null | `ScreenEffectRendererMixin` |
| Clipping 3e personne | `Camera.getMaxZoom(float)` renvoie la distance demandee | `CameraMixin` |
| Region (vanilla) | `RenderSectionRegion.getBlockState` / `getFluidState` : air hors region | `RenderSectionRegionMixin` |
| Region (Sodium) | `LevelSlice.getBlockState(BlockPos)`, `getBlockState(III)`, `getFluidState` | `compat.SodiumLevelSliceMixin` (`@Pseudo`) |

Choix techniques :
- **Priorite 2000** sur les mixins qui visent des methodes ou Sodium, Iris ou Voxy injectent aussi :
  applique apres eux, donc notre annulation en tete de methode s'execute la premiere.
- **`@Pseudo` + `targets` par nom** pour les classes de mods optionnels : aucune dependance de
  compilation, mixin ignore si le mod est absent, `require = 0` sur chaque injecteur.
- **Region** : apres chaque changement, `LevelRenderer.allChanged()` (intercepte par Sodium et Voxy)
  puis attente de `hasRenderedAllSections()` avec timeout. Les block entities hors region restent
  visibles sauf `hideBlockEntities`.
- Iris sans shader pack actif se comporte comme vanilla. Avec un pack, ciel, brouillard et fond
  passent par le pack : desactiver les shaders pour le mode studio.

Tools : `focus_entities`, `focus_scene`, `focus_region`, `focus_clear`, `focus_status`.

Validation en jeu (profil Modrinth avec Sodium, Iris sans pack, Voxy ; serveur Paper 26.1.2) :
- `focus_scene` terrain + ciel + particules + brouillard + fond `#202020` : fond parfaitement uni,
  entites intactes ; avec `focus_entities` en plus, seul le meuble Nexo reste a l'image.
- `focus_region` 8x7x8 blocs : seule la boite est rendue (dalle de terrain flottante), entites hors
  boite masquees, reconstruction complete en 450 ms a distance de rendu 32 avec Sodium ;
  `focus_clear` reconstruit en 590 ms et restaure tout.
- Les particules ne sont pas filtrees par la region (tout ou rien) : combiner avec `hideParticles`.
- Non exerces en jeu, hooks simples : `hideBlockEntities`, `hideInsideBlockOverlay`,
  `disableCameraClipping` (3e personne).

## Lot 3 : studio en monde (valide en jeu le 2026-09-09)

Realise le 2026-09-09. Deux tools : `frame_target` (cadrage, prise de vue, restauration) et
`studio_bounds` (meme calcul sans capture ni deplacement).

| Element | Point d'accroche (26.1.2) | Implementation |
|---|---|---|
| Eclairage plat | `EntityRenderDispatcher.extractEntity(E, float)` renvoie l'`EntityRenderState` de la frame ; `lightCoords = 15728880` (0xF000F0) est le plein jour | `EntityRenderDispatcherMixin`, option `flatLighting` de `focus_scene` |
| Encombrement de la cible | union, pour l'entite, ses passagers et les displays a moins de `attachRadius`, de `Display.getBoundingBoxForCulling()` quand elle est exploitable, sinon de la hitbox | `studio/StudioBounds` |
| Distance camera | projection des 8 coins de la boite dans le repere camera, contrainte `|lateral| <= profondeur * tan(demi-champ)` sur les deux axes, maximum retenu | `studio/Framing.fitDistance` (deplace au lot 3f) |
| Champ de vision | impose a 60 degres pendant la prise de vue puis restaure : le reglage joueur (110 ici) deforme le sujet et rend le cadrage non reproductible | `studio/StudioHandlers` |
| Position camera | vecteur de visee Minecraft depuis (yaw, pitch), recul depuis le centre | `studio/StudioHandlers` |
| Angles | azimut relatif a l'orientation de la cible, 9 vues nommees plus tourne-disque | `studio/StudioAngles` |
| Restauration | `FocusState.Snapshot`, mode de jeu et position sauvegardes puis restaures dans un `finally` | `studio/StudioHandlers` |

Choix techniques :
- **La hitbox d'un `item_display` est un point**, et le modele affiche peut faire plusieurs blocs.
  Mesure sur le meuble Nexo de test : hitbox reduite a un point, mais boite de visibilite de 4 sur 2
  sur 4 blocs (`getWidth`/`getHeight` valent 4 et 2). C'est la seule mesure du modele disponible
  cote client, d'ou son usage par defaut ; elle majore souvent le modele reel, ce que `margin`
  permet de corriger. Une boite degeneree ou aberrante (plus de 48 blocs) fait revenir a la hitbox.
- **Spectateur verifie avant tout deplacement** : si le serveur refuse `/gamemode spectator`,
  l'appel echoue au lieu de teleporter un joueur en survie dans le vide.
- **Plusieurs images par appel** : nouveau rendu `kind: "images"` cote MCP, un bloc image par vue
  plus les metadonnees de cadrage. Plafond de 12 vues par appel.
- **Une sphere englobante ne suffit pas** : elle laisse un sujet plat minuscule au centre d'un ecran
  large (mesure : 21 % de la largeur pour un meuble Nexo). L'ajustement aux coins utilise le champ
  horizontal reel et donne un cadrage serre a tous les angles.

Deux defauts trouves au premier essai en jeu et corriges :
- **Commandes vanilla reprises par EssentialsX** : `/tp @s` repond "Joueur introuvable" car le
  plugin attend un nom de joueur, et `/gamemode` suit ses propres regles. Les captures etaient
  prises depuis la position d'origine sans que rien ne le signale. D'ou `teleportCommand` et
  `gamemodeCommand` qualifies en `minecraft:...`, l'option `vanilla` de `chat.send`, et une
  verification de la position reelle de la camera apres chaque deplacement (`cameraDrift`, erreur
  `teleport_failed` au-dela d'un bloc).
- **Hauteur des yeux** : `/tp` place les pieds, la camera est 1,62 bloc plus haut, donc le sujet
  apparaissait tout en bas de l'image. La position de camera calculee est desormais abaissee de
  `player.getEyeHeight()`.

Troisieme defaut, trouve au test suivant : **la taille declaree par un display majore beaucoup le
modele affiche** (meuble Nexo : 4 sur 2 sur 4 blocs annonces pour un modele d'environ 1,5 bloc), ce
qui donnait un sujet minuscule au centre du cadre. Corrige en mesurant le sujet dans l'image plutot
qu'en faisant confiance aux dimensions declarees : sur un fond uni, tout pixel qui n'est pas la
couleur de fond appartient au sujet (`Images.contentBounds`).

Une correction proportionnelle unique ne suffit pas : la taille apparente n'evolue en `1/distance`
que pour un sujet lointain et plat, donc la premiere correction depasse la cible et coupe le sujet
(mesure : 30 % de remplissage a 4,57 blocs, sujet coupe a 1,83). Le reglage procede donc par
**dichotomie** : la bonne distance est encadree entre une valeur ou le sujet deborde et une ou il
tient entier, chaque passe verifie que rien ne touche les bords, et l'image renvoyee est la
meilleure prise non coupee, jamais la derniere. L'image finale est ensuite rognee sur le sujet
(`autoCrop`).

Valide en jeu : cadrage d'un meuble Nexo sous deux angles, reglage par dichotomie convergeant en
trois a cinq passes, passage et retour de spectateur, restauration de la position, du champ de
vision et du focus. Reste a essayer : un mob ModelEngine et le tourne-disque.

## Lot 3b : captures d'interface (valide en jeu le 2026-09-09)

Demande en cours de lot 3 : photographier un objet d'inventaire, une case precise, et le lore
affiche au survol.

| Element | Point d'accroche (26.1.2) | Implementation |
|---|---|---|
| Position de la souris vue par l'ecran | `MouseHandler.getScaledXPos(Window)` / `getScaledYPos(Window)`, lues par `GameRenderer.extractGui` et transmises a `Screen.extractRenderStateWithTooltipAndSubtitles` | `MouseHandlerMixin` + `util/GuiCursor` |
| Position des cases | `AbstractContainerScreen.leftPos` / `topPos` / `imageWidth` / `imageHeight` / `menu` (proteges) | `AbstractContainerScreenAccessor` |
| Contenu et infobulle | `AbstractContainerMenu.slots`, `Slot.x/y/index`, `Screen.getTooltipFromItem(Minecraft, ItemStack)` | `gui/GuiHandlers` |
| Ouverture de l'inventaire | `Minecraft.setScreen(new InventoryScreen(player))` | `gui.open` |
| Decoupage et agrandissement | `Images.encodeRegion`, facteur entier au plus proche voisin | `util/Images` |

Choix techniques :
- **Curseur virtuel plutot que deplacement reel** : la souris du joueur ne bouge pas, l'etat est
  restaure apres chaque capture, et rien n'est envoye au serveur (aucun clic accidentel).
- **Le texte du lore est renvoye en JSON** (`get_item_lore`) avec couleur et styles par segment :
  bien plus fiable et moins couteux qu'une lecture d'image pour verifier une mise en forme.
- **Position de l'infobulle estimee** en reproduisant la mise en page vanilla (largeur du texte,
  dix pixels par ligne, decalage de douze pixels, repli en bord d'ecran), avec une marge au
  decoupage puisque l'estimation reste approximative.
- Un menu de plugin est ouvert par le serveur : le mod ne fait que le lire une fois affiche.

Tools : `get_gui`, `open_inventory`, `close_gui`, `get_item_lore`, `hover_slot`, `screenshot_gui`.

Valide en jeu le 2026-09-09 sur l'inventaire du serveur de test :
- `gui.state` : 46 cases, 13 remplies, panneau et rectangle de chaque case corrects a l'echelle 2.
- `gui.tooltip` : lore restitue ligne par ligne avec les couleurs par segment (`gold`, `dark_gray`,
  `light_purple`, `#FE0117`), et les glyphes de police personnalisee visibles en clair
  (`\ue593`, `\uf001`), ce qu'une lecture d'image ne donnerait pas.
- `gui.screenshot` crop `slot` : texture de l'objet, agrandie x7 au plus proche voisin, nette.
- `gui.screenshot` crop `tooltip` avec `hoverSlot` : infobulle capturee, mais coupee en bas au
  premier essai. La geometrie a ete reprise sur les sources plutot qu'estimee : la zone de texte
  fait `max(largeur de ligne)` sur `lignes * 10` (moins deux pour une ligne unique), et le fond
  deborde de 12 pixels sur chaque cote (3 de marge plus 9 de cadre, `TooltipRenderUtil`), ancre au
  curseur decale de (+12, -12), avec repli a gauche si depassement a droite et remontee si
  depassement en bas (`DefaultTooltipPositioner`). Verifie ensuite avec une marge large : le cadre
  complet tient dans le rectangle calcule.
- Curseur virtuel et ecran restaures apres coup.
- Menu de plugin (81 cases, titre et fond en police et textures personnalisees) : lu et photographie
  comme l'inventaire, y compris des lores de 9 lignes avec degrade de couleur par caractere.

## Lot 3c : navigation dans les menus (valide en jeu le 2026-09-09)

Un menu de plugin s'etend souvent sur plusieurs pages et categories : sans clic, seule la premiere
page est observable. `gui.click` reproduit `AbstractContainerScreen.slotClicked` en appelant
`MultiPlayerGameMode.handleContainerInput(containerId, slotId, button, ContainerInput, Player)`.

Points verifies (26.1.2) :
- Le numero envoye est `Slot.index`, que `AbstractContainerMenu.addSlot` fixe a la place de la case
  dans le menu ; `Slot.getContainerSlot()` designe autre chose et n'est pas ce qu'attend le serveur.
- Sept types dans `ContainerInput` : `PICKUP`, `QUICK_MOVE`, `CLONE`, `THROW`, `SWAP`,
  `PICKUP_ALL`, `QUICK_CRAFT`.

Garde-fous, le clic etant la seule capacite du mod qui modifie l'etat du serveur :
- `enableGuiClicks` a `false` par defaut, avec un message d'erreur qui explique le risque ;
- `allowedClickTypes` limite a `pickup` et `quick_move`, suffisants pour naviguer ;
- `dryRun` renvoie la case visee et son contenu sans rien envoyer ;
- la reponse signale `screenChanged`, `menuReplaced` et `carried` (objet reste sur le curseur).

Valide en jeu : `dryRun` decrit la case sans rien envoyer, un clic sur une case vide ne change rien,
et un clic sur une categorie de la boutique ouvre bien le sous-menu correspondant.

## Lot 3d : animation et regressions visuelles (valide en jeu le 2026-09-09)

Deux manques identifies pendant le developpement : une capture ne montre qu'une pose, et rien ne
signale qu'une modification du pack a casse un modele voisin.

**Animation** (`vision.burst`) : serie de captures espacees, assemblees par defaut en une planche
unique (`Images.sheet`). Une animation rendue image par image coute tres cher a un modele ; une
planche montre le mouvement pour le prix d'une seule image. Mode image par image disponible.

**References** (`refs.*`) : chaque reference est une image plus la recette qui l'a produite, rangees
dans `mcbridge-refs/` du dossier de jeu. `refs.compare` rejoue la recette et compte les pixels dont
l'ecart depasse la tolerance (`Images.diff`), avec la zone touchee et une image des differences en
magenta. `refs.compareAll` donne le verdict de toutes les references en un appel, sans image, pour
etre enchaine apres `resources.reload`.

Points de conception :
- **La recette fait la valeur du procede** : sans position de camera memorisee, deux captures ne
  sont jamais comparables. Le mode `world` teleporte en spectateur puis restaure tout ; le mode
  `gui` capture le panneau de l'ecran ouvert et verifie que c'est bien le meme.
- **Resolution** : une fenetre redimensionnee entre l'enregistrement et la comparaison rendrait la
  comparaison fausse ; la nouvelle capture est alors ramenee a la taille de la reference et le
  resultat porte un drapeau `resized`.
- **Cout en tokens** : `compareAll` ne renvoie que des nombres, l'image ne vient qu'a la demande.

Valide en jeu le 2026-09-09 :
- Navigation dans un menu de plugin : clic sur la categorie Tenues de la boutique, sous-menu de 36
  objets ouvert, `menuReplaced` correctement detecte. Simulation et clic sans effet verifies avant.
- Planche d'animation : 8 vues sur 46 ticks assemblees en grille 4x2, mouvement lisible.
- References : enregistrement, rejeu et comparaison. Une rotation de camera de 6 degres donne 3,43 %
  de pixels differents, contre 0,2 % pour une scene vivante inchangee, soit un rapport d'environ 20.

Enseignement du test : **une scene vivante a un bruit de fond**. L'image des differences montrait
un joueur qui passait au loin et de legers mouvements autour du PNJ, ce qui depassait le seuil
initial de 0,05 %. Deux corrections : la recette memorise desormais un `focus` pour isoler son
sujet, et le seuil par defaut passe a 0,5 %, entre le bruit mesure et une vraie difference.

## Lot 3e : debit des commandes et masquage complet (valide en jeu le 2026-09-09)

Deux defauts trouves pendant la validation du lot 3d.

**Deconnexion pour spam.** La comparaison de references envoyait quatre commandes par reference
(mode de jeu aller-retour, teleportation aller-retour), et le reglage de cadrage une par passe. Le
serveur a deconnecte le joueur : chaque commande ajoute 20 a un compteur qui ne retombe que d'une
unite par tick, et 200 deconnecte, soit une dizaine de commandes rapprochees. Trois reponses :
- `util/Commands.send` espace tous les envois de `commandMinIntervalMs` (1100 ms par defaut),
  l'attente ayant lieu sur le thread HTTP et jamais sur le thread de rendu ;
- `util/Commands.moveCamera` deplace la camera cote client en spectateur, ou le serveur accepte la
  position annoncee, et ne retombe sur la commande que si la position obtenue ne correspond pas ;
- `refs.compareAll` ne change de mode de jeu qu'une fois pour toute la serie.

**Block entities visibles.** Masquer le terrain laissait panneaux, coffres et bannieres a l'image :
ils sont extraits par une passe distincte. L'option existait mais etait facile a oublier, d'ou le
raccourci `studio:true` de `focus_scene` qui allume tout le masquage d'un coup, et la nouvelle
option `hideAllEntities` pour photographier un decor sans aucune entite.

Valide en jeu : `refs.compareAll` sur deux references en 2 secondes, sans deconnexion. Le bruit de
scene tombe de 0,24 % a 0,0055 % quand la recette isole son sujet, soit un facteur 44, ce qui place
le seuil de 0,5 % tres au-dessus du bruit. Le raccourci `studio:true` allume bien les sept options,
et un rendu de PNJ ne laisse plus aucun element de decor.

## Correctif : chute apres une teleportation (implemente, a valider en jeu)

Signale par l'utilisateur : une capture demandee a une position en hauteur faisait tomber le joueur.
`studio.frameTarget` et `refs` passaient deja en spectateur, mais `vision.screenshot` teleportait
sans garde-fou, avec les degats de chute a l'arrivee.

`util/Spectator` regroupe le passage en spectateur et sa restauration, avec verification que le
serveur a suivi. `vision.screenshot` l'utilise quand un deplacement est demande (`stabilize`, actif
par defaut), et ne rend le mode precedent que si `returnToStart` est vrai : le rendre en plein vol
ferait tomber le joueur, ce que le garde-fou existe pour eviter.

Le vol en creatif a ete ecarte : le joueur resterait visible des autres joueurs et garderait ses
collisions, donc une camera placee dans un mur ne fonctionnerait pas. Le spectateur supprime en plus
le modele du joueur de l'image et evite la commande de teleportation, le serveur acceptant la
position annoncee par un spectateur.

## Lot 3f : robustesse du socle (fait, verifie hors jeu)

Revue d'architecture apres le lot 3e. Six defauts structurels, sans rapport avec une fonctionnalite
manquante, mais qui se payent tot ou tard.

| Defaut | Ce qui pouvait arriver | Correction |
|---|---|---|
| Aucune serialisation des actions | Deux captures en parallele (deux clients MCP branches, ou transport HTTP multi-session) se marchent dessus : la restauration de la premiere efface le reglage de la seconde | `RpcRouter.registerExclusive` et un verrou unique, refus en `busy` au-dela de `busyTimeoutMs` ; les lectures ne prennent pas le verrou |
| Sequence « sortir et revenir » ecrite trois fois | Une correction appliquee a un seul des trois appelants, comme le garde-fou de chute ajoute a `vision.screenshot` mais pas a `studio.frameTarget` | `util/Excursion` en try-with-resources, utilise par les trois |
| `frame_target` avec `returnToStart:false` | Le mode survie etait rendu a la position de la camera, souvent en l'air : le joueur tombait, exactement le bug corrige ailleurs | Regle inscrite dans `Excursion` : le mode n'est rendu que si la position l'a ete |
| Aucun test automatise | Une erreur de signe dans le cadrage ou un offset dans le protocole RCON ne se voit qu'a l'oeil, sur une image, apres coup | 51 tests cote mod, 31 cote serveur MCP, tous sans Minecraft |
| Contrat en double, rien pour le verifier | Une methode renommee d'un cote repond « Methode inconnue » au pire moment ; une centaine de parametres n'avaient pas de description | `scripts/check-contract.mjs`, et les descriptions ajoutees |
| Artefacts du plugin versionnes, jamais reconstruits | Le plugin publie n'expose pas les tools qu'on vient d'ajouter, sans aucun message d'erreur | `scripts/check-plugin.mjs` compare le contenu du jar et du bundle aux sources ; `build-plugin.sh` teste avant et verifie apres |

Deux corrections de surface au passage : cinq tools qui prennent le controle du client
(`screenshot`, `capture_animation`, `screenshot_gui`, `compare_reference`, `compare_all_references`)
s'annoncaient `readOnlyHint`, ce qui invitait le modele a les intercaler au milieu d'une prise de
vue ; et la reflexion creait une poignee reutilisable pour n'importe quelle classe, y compris celles
que la liste de blocage refuse de charger directement.

### Verification en jeu le 2026-09-10

Le socle a ete exerce sur le serveur de test, client Modrinth avec Sodium, Iris et Voxy, monde
`clicker_spawn`.

| Verification | Resultat |
|---|---|
| Deux actions exclusives en parallele | la seconde refusee en `busy` apres 30 s, message nommant `vision.burst` |
| Lecture pendant une action de 48 s | `info.status` repond en 22 ms |
| `frame_target` sur un mob ModelEngine (base invisible plus sept displays) | sujet isole sur fond uni, dichotomie convergee en cinq passes |
| Restauration apres cadrage | position au bit pres, mode de jeu, champ de vision et focus rendus |
| `frame_target` avec `returnToStart:false`, camera a 2 blocs du sol | joueur laisse en spectateur, aucune chute, aucun degat |
| `server_status` et `server_command` | version Paper, joueurs, sortie des commandes |
| `execute as @a at @s run say ...` avec `say` interdit | refuse par `CommandGuard` |
| Journal client | aucun avertissement de mcbridge, seulement ceux d'Iris et Voxy |

Deux defauts trouves a cette occasion, tous deux corriges et couverts par un test :

- **Une teleportation par la console changeait la dimension du joueur.** La console execute depuis
  l'overworld : `tp <joueur> x y z` a sorti le joueur de `clicker_spawn` pour le deposer aux memes
  coordonnees dans l'overworld, sans erreur. Les commandes console passent desormais par
  `minecraft:execute in <dimension> run ...` (`util/CommandLines`). Le chemin nominal ne l'avait pas
  revele parce qu'en spectateur la camera se deplace cote client, sans commande.
- **Un resultat de type tableau cassait la reponse MCP.** `structuredContent` n'accepte qu'un objet,
  et le client rejetait alors toute la reponse : `reflect_get_field` sur une liste ne renvoyait rien
  d'exploitable. Un tableau reste desormais dans le texte, sans partie structuree.

Cote RCON, `blockedCommands` ne regardait que le premier mot : `execute as @a run stop` arretait le
serveur sans jamais commencer par `stop`. `server/CommandGuard` extrait desormais toutes les tetes
de commande d'une ligne. C'est un garde-fou contre l'accident, pas contre l'intention : qui a le mot
de passe RCON a deja tous les droits.

## Lot 3g : cout en tokens (en cours, verifie hors jeu)

Mesure du 2026-09-10 sur les sessions passees : le poste dominant n'est pas la taille d'un
resultat mais le nombre de tours, chaque appel MCP relisant tout le contexte accumule (385 K et
520 K tokens de contexte median sur les deux longues sessions, pour 98 M et 350 M tokens relus).
Deuxieme poste : ce qui reste dans le contexte, image ou JSON, refacture a chaque tour suivant.

Fait :
- `run_steps` : plusieurs tools en un appel, tous valides avant la premiere execution, arret a la
  premiere erreur avec la liste de ce qui n'a pas tourne (`stopOnError=false` pour garantir un
  `focus_clear` final). La boucle commande, attente, focus, capture, focus_clear passe de cinq
  tours a un. Compose cote serveur MCP (`local: true` dans le catalogue), sans methode Java.
- Resultats compacts : JSON sans indentation et flottants arrondis (trois decimales a partir de 1,
  quatre chiffres significatifs en deca) dans `mcp-server/src/results.ts`. Mesure sur un resultat
  reel de `frame_target` : 10 938 caracteres indentes, 5 885 compacts et arrondis.
- `frame_target` renvoie un resume par defaut (`studio/StudioResult`, pur et teste) : cible,
  nombre de parties, taille et centre mesures, et par vue le nom de l'angle, la camera reellement
  utilisee apres reglage de la distance (nouveau champ `cameraUsed` du resultat complet), le
  remplissage et l'image. `verbose:true` rend le plan complet avec les passes de reglage. La ligne
  de texte par vue ne repete plus la camera. Sur le cas reel, le texte tombe d'environ 1 800 tokens
  a moins de 300.
- Image des differences (`Images.diff`) recadree sur `differenceBox` plus une marge, situee par
  `diffCrop`, et reduite a `diffMaxWidth` (640 par defaut) ; omise sous `diffImageMinPercent`
  (0,5 %, le bruit d'une scene vivante) avec la raison dans `diffImageOmitted`. De jusqu'a 1 843
  tokens a quelques centaines, et rien du tout quand rien n'a change.
- `compare_all_references` avec `includeDiffImages` mettait le base64 de chaque image dans le texte
  JSON, soit des dizaines de milliers de tokens par reference : nouveau genre `diffs` cote serveur
  MCP, une image par reference modifiee en bloc image.

- `list_entities` replie les passagers d'une entite listee dans son entree (`passengerIds`,
  `displayPassengers`, `foldedPassengers` au total) ; `groupPassengers:false` rend la liste plate.
  Un mob ModelEngine, base plus displays, ne fait plus qu'une entree. Mesure sur un resultat reel :
  14 entites deviennent 7 racines, 3 511 caracteres deviennent 1 831.
- `screenshot` : `defaultMaxWidth` passe de 1280 a 960 dans la config du mod, soit 1 229 tokens a
  691 par image en 16:9, refactures a chaque tour. Le fichier de config d'un client existant garde
  son ancienne valeur : la changer a la main (fait sur le profil de test).

Reste a faire, par impact decroissant :
- `reflect_get_field` : plafonner le texte renvoye. 500 elements sur trois niveaux peuvent faire
  30 K tokens d'un coup, pour toute la session.
- Catalogue : 51 tools, environ 15 K tokens quand le client ne differe pas les tools (Claude Code
  recent ne charge que les noms). Les cinq plus gros font un tiers du total ; raccourcir les
  descriptions de parametres et sortir les options rares dans un objet `advanced`.

Regle pour la suite : une image coute largeur x hauteur / 750 tokens, plafonnee a 1 568 px de grand
cote ; le format et la qualite JPEG n'y changent rien.

## Correctif : angle front sur les PNJ ModelEngine (verifie en jeu le 2026-09-10)

`frame_target` prenait l'orientation de la cible dans `Entity.getYRot()`, qui vaut 0 sur la base
d'un PNJ ModelEngine (`area_effect_cloud`) comme sur ses displays : "front" montrait le dos.
L'orientation est dans la rotation gauche de la transformation des displays, lue par
`Display.renderState().transformation().get(1).leftRotation()`.

Calibration en jeu, en lisant les rotations par reflexion et en photographiant deux PNJ depuis les
points cardinaux : identite regarde le sud, une rotation de `a` degres autour de +Y regarde vers le
yaw `-a` (PNJ a -166 degres tourne vers le nord, PNJ a -127 vers le nord-ouest), et le yaw d'entite
du display s'y ajoute, ce qui couvre un meuble Nexo oriente par son entite. `studio/Facing` fait
la moyenne circulaire des displays, ponderee par la lisibilite de chaque rotation, un os anime ou
une tete tournee pesant moins que le corps.

Piege trouve en chemin : les passagers de la base d'un PNJ sont des displays fantomes (objet
`custom_model_data` 1 invisible, billboard `VERTICAL`, rotation identite) et le modele visible est
porte par une seconde base au meme endroit. Un display qui suit la camera ne dit rien de son
orientation : `StudioBounds.facing` ignore les billboards `VERTICAL` et `CENTER`, les textes et les
objets vides, puis se replie sur les displays attaches dans le rayon quand la cible n'a rien
d'exploitable. Le resultat complet porte `targetYawSource` (`displays`, `attachedDisplays`,
`entity`).

Verifie apres redemarrage sur les deux PNJ, par leurs deux bases chacun : la base fantome passe par
les displays attaches (yaw 165 et 127), le modele visible par ses propres displays (161 et 127),
et `front` montre la face dans les quatre cas.

## Lot 4 : rendu hors ecran

Reproduire le mecanisme vanilla qui dessine le joueur dans l'inventaire :
- `PictureInPictureRenderer<T>` (`gui/render/pip/`) : textures GPU couleur + profondeur, projection
  propre, `prepare`, `renderToTexture(T, PoseStack)`.
- `GuiEntityRenderer` : rend un `EntityRenderState` via `EntityRenderDispatcher`.
- `GuiEntityRenderState(EntityRenderState, Vector3f translation, Quaternionf rotation, Quaternionf overrideCameraAngle, int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissor)`.
- `TextureTarget(String, int, int, boolean)` pour une cible de rendu a resolution libre.
- `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` accepte n'importe quelle cible.

Tool vise : `studio_render(uuid|type, width, height, angles|turntable, ortho, background|transparent)`
sans deplacer le joueur. Contrainte : la cible doit etre chargee cote client ; un modele
ModelEngine est capture dans la pose du tick courant (groupe base + displays).

## Lot 5 : confort et regression

- Sprite sheets et PNG transparents pour la documentation.
- Images de reference et comparaison pixel a pixel pour detecter un changement de modele apres une
  modification du pack.
- Transport HTTP streamable teste avec Claude Desktop.
- Build multi-version (Stonecutter) si le serveur passe en 26.2.
