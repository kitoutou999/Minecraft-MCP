# Reference des tools

Fichier genere par `scripts/gen-tools-doc.mjs` depuis `mcp-server/src/tools.ts`. Ne pas editer a la main.

51 tools. Convention : yaw 0 = sud, 90 = ouest, -90 = est, 180 = nord ; pitch -90 = haut, 90 = bas ; 20 ticks = 1 s.

## info

### `get_status`

Methode RPC `info.status`, lecture.

A appeler en premier. Renvoie la version du mod et de Minecraft, si le joueur est dans un monde, le serveur rejoint, les capacites activees dans la config (vision, commands, clientOptions, focus, reflection), le tick courant et la liste des methodes RPC.

Sans parametre.

## player

### `get_player`

Methode RPC `player.getState`, lecture.

Position, position des yeux, yaw/pitch, dimension, mode de jeu, vie, faim, vol, spectateur, accroupi, sprint. Convention : yaw 0 = sud, 90 = ouest, -90 = est, 180 = nord ; pitch -90 = haut, 90 = bas.

Sans parametre.

## camera

### `get_camera`

Methode RPC `camera.get`, lecture.

Position et orientation reelles de la camera de rendu (differentes du joueur en 3e personne), plus celles du joueur.

Sans parametre.

### `look`

Methode RPC `camera.look`, action.

Oriente la vue du joueur. Donner yaw/pitch absolus ou deltaYaw/deltaPitch relatifs. Le corps suit la tete. Pour deplacer le joueur, utiliser send_command avec /tp (ou le parametre teleport de screenshot).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `yaw` | number | non |  | Yaw absolu en degres. |
| `pitch` | number | non |  | Pitch absolu en degres. |
| `deltaYaw` | number | non |  | Rotation horizontale relative. |
| `deltaPitch` | number | non |  | Rotation verticale relative. |

### `look_at`

Methode RPC `camera.lookAt`, action.

Oriente la vue du joueur vers un point du monde (calcule yaw/pitch depuis la position des yeux).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `x` | number | oui |  | Coordonnee X (est/ouest). |
| `y` | number | oui |  | Coordonnee Y (hauteur). |
| `z` | number | oui |  | Coordonnee Z (nord/sud). |

## vision

### `screenshot`

Methode RPC `vision.screenshot`, action, renvoie une image.

Capture le rendu du client et renvoie une image (JPEG par defaut, redimensionnee a maxWidth). Options : teleport {x,y,z,yaw?,pitch?} deplace le joueur avant la capture, en spectateur par defaut pour qu'il ne tombe pas ; look {yaw,pitch} oriente la vue ; hideHud (defaut true) masque l'interface ; waitTicks (defaut 2, ou 10 apres teleport) laisse le temps aux chunks et modeles de charger, 20 ticks = 1 s. Le HUD est restaure apres la capture. Un ecran ouvert (chat, menu Echap, inventaire) est masque par defaut le temps de la capture sans etre ferme (hideScreen) ; la reponse indique screenOpen et screenHidden. Utiliser format 'png' et maxWidth 0 pour une image fidele en pleine resolution (couteuse en tokens).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `teleport` | {x, y, z, yaw, pitch} | non |  | Deplacer le joueur avant la capture. Les coordonnees designent ses pieds, comme une commande de teleportation. |
| `stabilize` | boolean | non | true | Passer en spectateur avant de deplacer le joueur, pour qu'il ne tombe pas si la position est en l'air, et pour qu'aucune collision ni modele de joueur ne gene la capture. Sans lui, une position en hauteur fait chuter un joueur en survie. Le mode precedent n'est rendu que si returnToStart est vrai : le rendre en plein vol ferait tomber le joueur. |
| `returnToStart` | boolean | non | false | Revenir a la position et au mode de jeu du depart apres la capture. Sinon le joueur reste sur place. |
| `look` | {yaw, pitch} | non |  | Orienter la vue avant la capture. |
| `hideHud` | boolean | non | true | Masquer le HUD pendant la capture. |
| `hideScreen` | boolean | non | true | Ne pas dessiner l'ecran ouvert (chat, menu Echap, inventaire, menu serveur) pendant la capture, sans le fermer ni rendre la souris au jeu. |
| `closeScreen` | boolean | non | false | Fermer reellement l'ecran ouvert avant la capture (perd un inventaire ou un menu serveur ; rend la souris au jeu). Rarement utile, preferer hideScreen. |
| `waitTicks` | int | non |  | Ticks a attendre avant la capture. |
| `maxWidth` | int | non |  | Largeur max de l'image renvoyee (0 = native). Defaut : config du mod (960, environ 690 tokens en 16:9). 1280 coute 1230 tokens, a reserver a un detail fin. |
| `format` | "jpeg" ou "png" | non |  | Format de sortie. Defaut : config du mod (jpeg). |
| `quality` | number | non |  | Qualite JPEG (defaut 0.85). |

### `describe_scene`

Methode RPC `vision.describeScene`, lecture.

Description textuelle de ce que vise le joueur (bloc avec face, ou entite) et des entites proches triees par distance, avec leur type, nom, position et si ce sont des entites display (ModelEngine, Nexo). Moins couteux qu'un screenshot.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `radius` | number | non | 32 | Rayon de recherche des entites. |
| `maxEntities` | int | non | 50 | Nombre maximal d'entites listees (defaut 50), les plus proches d'abord. |

### `capture_animation`

Methode RPC `vision.burst`, action.

Prend une serie de captures espacees dans le temps, depuis le point de vue actuel. Une image seule ne dit rien d'une animation ModelEngine, d'un effet de particules, d'un sort MythicMobs ou d'une transition de HUD : cette serie montre le mouvement. Par defaut les images sont assemblees en une planche unique, lue de gauche a droite puis de haut en bas, ce qui coute une seule image au lieu de plusieurs. Passer layout 'frames' quand le detail de chaque instant compte plus que le nombre de vues. Se placer d'abord (send_command tp, look_at), et masquer le decor avec focus_scene si le sujet doit ressortir.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `frames` | int | non | 6 | Nombre de captures. |
| `intervalTicks` | int | non | 4 | Ecart entre deux captures, 20 ticks = 1 s. |
| `layout` | "sheet" ou "frames" | non | "sheet" | 'sheet' une planche unique, 'frames' une image par instant. |
| `cellWidth` | int | non | 320 | Largeur d'une vignette de la planche. |
| `columns` | int | non | 0 | Colonnes de la planche (0 = grille la plus carree). |
| `maxWidth` | int | non |  | Largeur de chaque image en mode 'frames'. |
| `hideHud` | boolean | non | true | Masquer l'interface pendant la serie (defaut true). |
| `hideScreen` | boolean | non | true | Masquer l'ecran ouvert sans le fermer (defaut true). |
| `format` | "jpeg" ou "png" | non | "jpeg" | 'jpeg' (defaut, peu couteux en tokens) ou 'png' (fidele). |
| `quality` | number | non |  | Qualite JPEG entre 0 et 1 (defaut 0.85). Sans effet en PNG. |

## refs

### `save_reference`

Methode RPC `refs.save`, action.

Capture une image et la range sous un nom, avec la recette exacte qui l'a produite : position et orientation de camera, champ de vision, options de scene. C'est la recette qui rend la comparaison possible plus tard. Mode 'world' (defaut) : la camera est celle du joueur au moment de l'appel, sauf si camera est fourni ; entierement reproductible. Mode 'gui' : le panneau de l'interface ouverte ; la comparaison exigera que le meme menu soit ouvert, ce que le mod ne peut pas provoquer pour un menu de plugin. A poser une fois sur chaque element a surveiller, avant de toucher au pack. Pour une reference fiable, isoler le sujet avec focus et masquer le decor avec scene : une scene vivante bouge d'une capture a l'autre.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `name` | string | oui |  | Nom court, lettres chiffres point tiret souligne. |
| `mode` | "world" ou "gui" | non | "world" | 'world' (defaut) memorise la position de camera et rejoue la vue ; 'gui' capture le panneau de l'ecran ouvert. |
| `note` | string | non |  | A quoi sert cette reference, pour s'y retrouver plus tard. |
| `camera` | {x, y, z, yaw, pitch} | non |  | Position des yeux de la camera. Par defaut celle du joueur. |
| `fov` | int | non |  | Champ de vision impose a l'enregistrement et rejoue tel quel (defaut : celui du client). |
| `scene` | {hideTerrain, hideSky, hideParticles, hideBlockEntities, disableFog, flatLighting, backgroundColor} | non |  | Options de scene a appliquer, memorisees dans la recette. Un fond uni et un eclairage plat rendent la comparaison plus stable. |
| `focus` | {uuids, types, attachRadius, hideOthers, hideSelf} | non |  | Entites a garder au rendu, memorisees dans la recette. Fortement conseille : sans isolation, un joueur qui passe dans le champ ou une entite qui bouge suffit a faire diverger la comparaison (environ 0,2 % de pixels de bruit mesures sur une scene vivante). |
| `hideHud` | boolean | non | true | Masquer l'interface (defaut true). Memorise dans la recette. |
| `waitTicks` | int | non | 6 | Ticks d'attente avant la capture, le temps que chunks et modeles chargent (defaut 6, 20 = 1 s). |
| `overwrite` | boolean | non | false | Remplacer une reference du meme nom (defaut false : l'appel echoue si elle existe). |

### `compare_reference`

Methode RPC `refs.compare`, action.

Rejoue la recette d'une reference et compare le resultat a l'image enregistree, pixel par pixel. Renvoie la part de pixels differents, l'ecart maximal, la zone touchee (differenceBox), et une image ou les differences ressortent en magenta, recadree sur la zone touchee avec une marge (diffCrop la situe dans le cadre) et reduite a diffMaxWidth. Sous diffImageMinPercent de pixels differents, l'image est omise : c'est le bruit d'une scene vivante, pas un changement. Sert a repondre a 'est-ce que ma modification a casse ce modele'.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `name` | string | oui |  | Nom de la reference a rejouer. |
| `tolerance` | int | non | 8 | Ecart tolere par canal avant de compter un pixel comme different. |
| `includeDiffImage` | boolean | non | true | Joindre l'image des differences, en magenta sur fond grise (defaut true). |
| `diffMaxWidth` | int | non | 640 | Largeur maximale de l'image des differences apres recadrage (defaut 640, 0 = taille de la reference). |
| `diffImageMinPercent` | number | non | 0.5 | Part de pixels differents en dessous de laquelle l'image des differences n'est pas jointe (defaut 0,5 %, le bruit d'une scene vivante). 0 pour toujours la joindre. |

### `compare_all_references`

Methode RPC `refs.compareAll`, action.

Rejoue toutes les references et renvoie un verdict chiffre pour chacune, sans image : c'est l'appel a faire apres reload_resource_pack pour savoir d'un coup ce qui a change visuellement. Relancer compare_reference sur celles qui ont bouge pour voir la difference. Chaque reference en mode 'world' deplace brievement le joueur puis le remet en place.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `tolerance` | int | non | 8 | Ecart tolere par canal de couleur avant de compter un pixel comme different (0 a 255, defaut 8). |
| `changedThresholdPercent` | number | non | 0.5 | Part de pixels differents a partir de laquelle une reference est declaree modifiee. Mesures sur une scene vivante : environ 0,2 % de bruit sans rien changer, contre 3 % pour une vraie difference. Baisser le seuil si les references isolent bien leur sujet. |
| `includeDiffImages` | boolean | non | false | Joindre l'image des differences, recadree et reduite, pour chaque reference declaree modifiee. Une image par reference. |
| `diffMaxWidth` | int | non | 640 | Largeur maximale de chaque image des differences (defaut 640). |

### `list_references`

Methode RPC `refs.list`, lecture.

Noms, mode, note et resolution des references enregistrees, avec le dossier ou elles sont rangees.

Sans parametre.

### `delete_reference`

Methode RPC `refs.delete`, action.

Supprime l'image et la recette d'une reference.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `name` | string | oui |  | Nom de la reference a supprimer, image et recette comprises. |

## world

### `get_block`

Methode RPC `world.getBlock`, lecture.

Identifiant et proprietes du bloc a une position entiere, tel que le client le connait (chunk charge requis).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `x` | int | oui |  | Coordonnee X entiere du bloc. |
| `y` | int | oui |  | Coordonnee Y entiere du bloc (hauteur). |
| `z` | int | oui |  | Coordonnee Z entiere du bloc. |

### `list_entities`

Methode RPC `world.entitiesNearby`, lecture.

Entites chargees dans un rayon autour du joueur (ou d'un centre donne), triees par distance. Filtres : types (ex. ['zombie','minecraft:item_display']), includeDisplays, includePlayers, includeSelf. Sert a trouver l'UUID ou l'id d'une cible pour focus_entities, frame_target et get_entity. Les passagers d'une entite listee (os ModelEngine, montures) sont replies dans son entree : passengerIds et displayPassengers, le nombre de displays qu'elle porte ; cibler la base suffit, frame_target et focus_entities prennent ses displays avec elle. groupPassengers:false pour la liste plate.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `radius` | number | non | 32 | Rayon de recherche en blocs (defaut 32, maximum 256). |
| `center` | {x, y, z} | non |  | Centre de recherche (defaut : le joueur). |
| `types` | string[] | non |  | Types a garder, avec ou sans prefixe minecraft:. |
| `includeDisplays` | boolean | non | true | Inclure les entites d'affichage, os ModelEngine et meubles Nexo compris (defaut true). |
| `includePlayers` | boolean | non | true | Inclure les autres joueurs (defaut true). |
| `includeSelf` | boolean | non | false | Inclure le joueur local (defaut false). |
| `max` | int | non | 200 | Nombre maximal d'entites renvoyees, les plus proches d'abord (defaut 200). |
| `groupPassengers` | boolean | non | true | Replier les passagers d'une entite listee dans son entree (defaut true) ; false pour lister chaque passager a part. |

### `get_entity`

Methode RPC `world.getEntity`, lecture.

Detail d'une entite par uuid ou id : boite englobante (pour cadrer la camera), passagers, et entites display situees a moins de attachRadius (les os d'un modele ModelEngine, les parties d'un meuble Nexo).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `uuid` | string | non |  | UUID de l'entite. Fournir uuid ou id. |
| `id` | int | non |  | Id reseau de l'entite (change a chaque session). |
| `attachRadius` | number | non | 4 | Rayon en blocs ou chercher les displays attaches a l'entite (defaut 4). |

## chat

### `send_command`

Methode RPC `chat.send`, action.

Execute une commande en tant que joueur (sans le slash initial, il est ajoute). Le joueur doit avoir la permission sur le serveur. Exemples : 'tp @s 100 80 100', 'gamemode spectator', 'time set noon', 'mm mobs spawn boss 1'. La sortie de la commande arrive dans le chat : lire get_chat ou poll_events ensuite. vanilla:true force l'implementation vanilla en prefixant 'minecraft:', indispensable quand un plugin redefinit la commande (sur un serveur avec EssentialsX, /tp, /gamemode, /time ou /weather ont une autre syntaxe et rejettent les selecteurs comme @s).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `command` | string | oui |  | Commande sans le slash initial. |
| `vanilla` | boolean | non | false | Prefixer 'minecraft:' pour viser la commande vanilla plutot que celle d'un plugin. |

### `send_chat`

Methode RPC `chat.send`, action.

Envoie un message de chat en tant que joueur (un message commencant par / est traite comme commande).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `message` | string | oui |  | Message a dire dans le chat public. Un message commencant par / partirait comme commande : utiliser send_command. |

### `get_chat`

Methode RPC `chat.recent`, lecture.

Derniers messages recus : chat des joueurs, messages systeme, retours de commandes.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `limit` | int | non | 50 | Nombre de messages recents renvoyes (defaut 50). |

## events

### `poll_events`

Methode RPC `events.poll`, lecture.

Evenements recents du client : chat, system_message, join, disconnect, focus_changed, resources_reloaded. Passer sinceId (le lastId de l'appel precedent) pour ne recevoir que les nouveaux.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `limit` | int | non | 100 | Nombre maximal d'evenements renvoyes (defaut 100). |
| `types` | string[] | non |  | Types a garder : chat, system_message, join, disconnect, focus_changed, resources_reloaded. |
| `sinceId` | int | non | 0 | Ne renvoyer que les evenements d'identifiant superieur, pour une lecture incrementale. |

## client

### `get_client_options`

Methode RPC `client.getOptions`, lecture.

hideGui, fov, guiScale, renderDistance, gamma, taille de la fenetre.

Sans parametre.

### `set_client_options`

Methode RPC `client.setOptions`, action.

Modifie une ou plusieurs options : hideGui (HUD), fov (30-110), guiScale (0 = auto), renderDistance (2-64), gamma (0-1). Les options non fournies sont inchangees. Renvoie les nouvelles valeurs.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `hideGui` | boolean | non |  | Masquer l'interface, comme la touche F1. |
| `fov` | int | non |  | Champ de vision vertical en degres (30 a 110). |
| `guiScale` | int | non |  | Echelle de l'interface ; 0 vaut automatique. |
| `renderDistance` | int | non |  | Distance de rendu en chunks (2 a 32). |
| `gamma` | number | non |  | Luminosite entre 0 et 1 ; au-dela de 1 le jeu eclaircit fortement les ombres. |

## resources

### `reload_resource_pack`

Methode RPC `resources.reload`, action.

Recharge packs de ressources, modeles, textures et shaders, comme F3+T. A faire apres une modification du pack. Peut prendre plusieurs secondes. Verifier ensuite get_client_log avec levels ['ERROR','WARN'] pour les erreurs de modeles.

Sans parametre.

## logs

### `get_client_log`

Methode RPC `logs.client`, lecture.

Dernieres lignes de logs/latest.log du client, avec filtre regex optionnel et filtre de niveaux (ERROR, WARN, INFO). C'est la que remontent les erreurs de textures, modeles et shaders apres un rechargement.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `lines` | int | non | 100 | Nombre de dernieres lignes lues (defaut 200). Le fichier peut faire plusieurs Mo. |
| `filter` | string | non |  | Regex, insensible a la casse. |
| `levels` | string[] | non |  | Ex. ['ERROR','WARN']. |
| `file` | string | non | "latest.log" | Nom du fichier dans logs/ (defaut latest.log). |

## game

### `wait_ticks`

Methode RPC `game.waitTicks`, lecture.

Attend N ticks client (20 = 1 seconde, max 600). Utile apres une commande qui fait apparaitre une entite ou charge des chunks.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `ticks` | int | non | 20 | Nombre de ticks a attendre, 20 ticks valant une seconde. |

## mcp

### `run_steps`

Compose par le serveur MCP, sans methode RPC dans le mod, action.

Execute plusieurs tools du catalogue a la suite, en un seul aller-retour : chaque etape nomme un tool et ses arguments, exactement comme un appel direct. Toutes les etapes sont validees avant que la premiere ne parte, puis executees dans l'ordre, et les resultats reviennent dans le meme ordre, images comprises. A la premiere erreur, l'execution s'arrete et les etapes restantes sont nommees (stopOnError=false pour tout executer malgre tout, par exemple pour garantir un focus_clear final). A utiliser des que deux appels dependent l'un de l'autre : chaque appel separe coute un tour complet de conversation. Exemple : send_command (tp), wait_ticks, focus_entities, screenshot, focus_clear. run_steps ne peut pas s'appeler lui-meme.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `steps` | {tool, args}[] | oui |  | Etapes dans l'ordre d'execution, 20 au plus. |
| `stopOnError` | boolean | non | true | Arreter a la premiere etape en erreur (defaut), ou executer toutes les etapes. |

## focus

### `focus_entities`

Methode RPC `focus.set`, action.

Ne rend que les entites selectionnees (par uuids, ids ou types) et masque toutes les autres, sans toucher au serveur. Inclut par defaut les passagers et les entites display a moins de attachRadius (os ModelEngine, parties Nexo). Masque aussi le joueur (hideSelf) et toute entite qui contiendrait la camera (hideCameraOccluders). Fusion : les options absentes sont inchangees ; la scene (focus_scene) et la region (focus_region) sont independantes. Reste actif jusqu'a focus_clear.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `uuids` | string[] | non |  | UUID des entites a garder visibles. |
| `ids` | int[] | non |  | Identifiants numeriques d'entites a garder visibles. |
| `types` | string[] | non |  | Ex. ['zombie'] ou ['minecraft:item_display']. |
| `attachRadius` | number | non |  | Rayon d'attache des displays autour des entites selectionnees (defaut 4). |
| `includePassengers` | boolean | non |  | Garder aussi les passagers et le vehicule de la cible (defaut true). |
| `includeAttachedDisplays` | boolean | non |  | Garder les displays proches de la cible : sans eux, un modele ModelEngine ou Nexo disparait (defaut true). |
| `hideOthers` | boolean | non |  | Masquer toutes les entites non selectionnees (defaut true). |
| `hideSelf` | boolean | non |  | Masquer le joueur local (defaut true). |
| `hideCameraOccluders` | boolean | non |  | Masquer une entite qui englobe la camera et boucherait la vue (defaut true). |

### `focus_scene`

Methode RPC `focus.set`, action.

Masque des elements du rendu pour isoler un sujet, sans toucher au monde. Le plus simple est studio:true, qui allume tout le masquage d'un coup, avec backgroundColor pour le fond. Attention : masquer le terrain ne suffit pas, panneaux, coffres et bannieres sont des block entities dessinees a part et demandent hideBlockEntities, d'ou l'interet du raccourci. Fusion : les options absentes sont inchangees, et les options explicites priment sur le raccourci. Reste actif jusqu'a focus_clear.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `studio` | boolean | non |  | Raccourci : allume d'un coup terrain, ciel, particules, block entities, brouillard, overlay et eclairage plat. Les options explicites restent prioritaires. |
| `hideTerrain` | boolean | non |  | Blocs, y compris le terrain lointain de Voxy. |
| `hideSky` | boolean | non |  | Ciel, nuages et meteo. |
| `hideParticles` | boolean | non |  | Masquer les particules ; elles continuent d'exister, elles ne sont plus dessinees. |
| `hideBlockEntities` | boolean | non |  | Panneaux, coffres, bannieres, tetes : ils sont dessines par une passe distincte du terrain et restent visibles sans cette option. |
| `hideAllEntities` | boolean | non |  | Masquer toutes les entites, meme celles selectionnees par focus_entities : pour photographier un decor seul. |
| `disableFog` | boolean | non |  | Supprimer le brouillard, sur les entites comme sur le terrain. |
| `disableCameraClipping` | boolean | non |  | Empeche la camera de se rapprocher quand un bloc la gene, en troisieme personne. |
| `hideInsideBlockOverlay` | boolean | non |  | Supprimer la texture plein ecran affichee quand la camera est dans un bloc. |
| `flatLighting` | boolean | non |  | Eclairer les entites comme en plein jour, quelle que soit la lumiere reelle. |
| `backgroundColor` | string | non |  | '#RRGGBB' ou 'none'. |

### `focus_region`

Methode RPC `focus.region`, action.

Ne rend que les blocs de la boite [from, to] (bornes incluses) : tout le reste est vu comme de l'air, y compris par Sodium, et les entites hors de la boite sont masquees (hideEntitiesOutside). Declenche une reconstruction de toutes les sections et attend qu'elle soit terminee (waitForRebuild, jusqu'a timeoutMs), ce qui peut prendre plusieurs secondes selon la distance de rendu. clear:true retire la region. Cumulable avec focus_entities et focus_scene.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `from` | {x, y, z} | non |  | Premier coin (blocs). |
| `to` | {x, y, z} | non |  | Coin oppose (blocs). |
| `hideEntitiesOutside` | boolean | non | true | Masquer aussi les entites hors de la boite (defaut true). |
| `clear` | boolean | non | false | Retirer la region. |
| `waitForRebuild` | boolean | non | true | Attendre la fin de la reconstruction des sections avant de repondre (defaut true) : sinon la capture suivante montrerait un terrain a moitie refait. |
| `timeoutMs` | int | non | 20000 | Delai maximal d'attente de la reconstruction (defaut 20000). |

### `focus_clear`

Methode RPC `focus.clear`, action.

Retablit le rendu normal : selection d'entites, scene et region. Reconstruit les sections si une region etait active.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `waitForRebuild` | boolean | non | true | Attendre la reconstruction du terrain avant de repondre (defaut true). |

### `focus_status`

Methode RPC `focus.status`, lecture.

Selection d'entites courante (avec le nombre d'entites selectionnees chargees), region et options de scene.

Sans parametre.

## server

### `server_status`

Methode RPC `server.status`, lecture.

Dit si le canal RCON est configure et joignable, et si oui renvoie la version du serveur et les joueurs connectes. RCON est facultatif : sans lui tout fonctionne, mais les teleportations et changements de mode passent par des commandes envoyees en tant que joueur, avec les permissions et la limite anti-spam que cela implique. A appeler avant server_command pour savoir si le canal existe.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `includePlugins` | boolean | non | false | Joindre la liste des plugins du serveur. Sortie longue. |

### `server_command`

Methode RPC `server.command`, action.

Execute une commande sur le serveur en tant que console et RENVOIE SA SORTIE, ce qu'une commande envoyee en tant que joueur ne permet pas. C'est la porte vers tout ce que le client ignore : catalogue d'objets d'un plugin, liste de ses mobs, joueurs hors ligne, monde au-dela de la distance de rendu. Exemples selon les plugins installes : 'list', 'plugins', 'mm mobs list', 'nexo items', 'lp user <joueur> info'. Demande RCON configure : verifier avec server_status. Attention, la console a tous les droits et n'a pas de 'soi', donc pas de selecteur @s : nommer explicitement le joueur. Quelques commandes sont refusees par la configuration du mod, dont l'arret du serveur.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `command` | string | oui |  | Commande sans le slash initial. |

## gui

### `get_gui`

Methode RPC `gui.state`, lecture.

Decrit l'ecran ouvert : nom, titre, position et taille du panneau, echelle de l'interface, et pour chaque case son numero, son rectangle a l'ecran et l'objet qu'elle contient (identifiant, quantite, nom affiche, nombre de lignes d'infobulle). Sans image, donc sans cout en tokens : a appeler avant toute capture d'interface pour choisir la case. Marche pour l'inventaire du joueur comme pour un menu ouvert par un plugin.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `includeEmpty` | boolean | non | false | Inclure les cases vides. |
| `includeTooltipLineCount` | boolean | non | true | Joindre le nombre de lignes d'infobulle de chaque case (defaut true), utile pour prevoir le decoupage. |

### `open_inventory`

Methode RPC `gui.open`, action.

Ouvre l'inventaire du joueur cote client, comme la touche E, et renvoie l'etat de l'interface. Un menu de plugin ne s'ouvre pas ainsi : lancer sa commande avec send_command, puis lire get_gui.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `screen` | "inventory" | non | "inventory" | Seul 'inventory' est ouvrable par le mod ; un menu de plugin s'ouvre par sa commande serveur. |

### `close_gui`

Methode RPC `gui.close`, action.

Ferme l'ecran ouvert et desactive le curseur virtuel.

Sans parametre.

### `get_item_lore`

Methode RPC `gui.tooltip`, lecture.

Renvoie le texte exact de l'infobulle d'une case : chaque ligne avec son texte brut, puis ses segments avec couleur (#rrggbb ou nom vanilla), gras, italique, souligne, barre, obfusque. C'est le moyen le moins couteux et le plus fiable de verifier un lore : couleurs, ordre des lignes, italique parasite. Utiliser screenshot_gui pour le rendu visuel.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `slot` | int | oui |  | Numero de case, donne par get_gui. |

### `hover_slot`

Methode RPC `gui.hover`, action.

Place un curseur virtuel sur une case (ou aux coordonnees x, y de l'interface) pour afficher son infobulle a l'ecran. La souris reelle du joueur ne bouge pas. Le survol reste actif jusqu'a clear:true ou la fermeture de l'interface. Pour une simple capture, passer plutot hoverSlot a screenshot_gui, qui remet le curseur en etat ensuite.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `slot` | int | non |  | Numero de la case a survoler, tel que renvoye par get_gui. Omettre avec clear:true. |
| `x` | number | non |  | Coordonnee X en unites d'interface. |
| `y` | number | non |  | Coordonnee Y en unites d'interface. |
| `clear` | boolean | non | false | Desactiver le curseur virtuel. |

### `screenshot_gui`

Methode RPC `gui.screenshot`, action, renvoie une image.

Capture l'interface ouverte, avec decoupage. crop : 'gui' le panneau du menu (defaut), 'slot' une seule case (agrandie au plus proche voisin pour rester nette), 'tooltip' l'infobulle du survol, 'rect' une zone libre, 'none' tout l'ecran. hoverSlot affiche l'infobulle de la case pendant la capture puis remet le curseur en etat. Pour verifier un lore : hoverSlot avec crop 'tooltip'. Pour verifier une texture ou un modele : crop 'slot'. Le HUD est masque par defaut, l'interface reste visible. Format png par defaut, mieux adapte au texte et aux textures.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `crop` | "gui" ou "slot" ou "tooltip" ou "rect" ou "none" | non | "gui" | Zone capturee : 'gui' le panneau (defaut), 'slot' une case, 'tooltip' l'infobulle survolee, 'rect' une zone donnee, 'none' tout l'ecran. |
| `slot` | int | non |  | Case a decouper avec crop:'slot'. |
| `hoverSlot` | int | non |  | Case a survoler pour afficher son infobulle. |
| `rect` | {x, y, width, height} | non |  | Zone a decouper avec crop:'rect', en unites d'interface. |
| `padding` | int | non |  | Marge autour du decoupage (defaut 2 pour une case, 6 sinon). |
| `maxWidth` | int | non | 900 | Largeur maximale de l'image renvoyee (defaut 900). |
| `minWidth` | int | non |  | Largeur minimale : un decoupage etroit est agrandi (defaut 256 pour une case). |
| `format` | "png" ou "jpeg" | non | "png" | 'png' (defaut, fidele pour une texture) ou 'jpeg'. |
| `quality` | number | non |  | Qualite JPEG entre 0 et 1. Sans effet en PNG. |
| `hideHud` | boolean | non | true | Masquer le HUD du jeu derriere l'interface (defaut true). |
| `waitTicks` | int | non | 3 | Ticks d'attente avant la capture, le temps que le survol s'affiche (defaut 3). |

### `click_slot`

Methode RPC `gui.click`, action.

Clique une case de l'interface ouverte, comme le joueur le ferait. C'est ce qui permet de parcourir un menu de plugin tout seul : cliquer une categorie ou une page suivante, puis relire get_gui. Renvoie l'etat complet de l'interface apres le clic, avec screenChanged et menuReplaced pour savoir si le serveur a ouvert un autre menu. ATTENTION : le clic part reellement au serveur. Dans un menu de plugin il declenche l'action de la case, qui peut etre un achat ou une vente autant qu'une navigation ; dans un inventaire il deplace des objets, et le champ 'carried' signale un objet reste sur le curseur. Utiliser dryRun:true pour verifier la cible sans rien envoyer, et lire le lore de la case avant de cliquer quand l'effet n'est pas evident. Desactive par defaut cote mod (enableGuiClicks), et limite aux types pickup et quick_move.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `slot` | int | oui |  | Numero de case, donne par get_gui. |
| `button` | int | non | 0 | 0 clic gauche, 1 clic droit ; avec type 'swap', numero de la case de la barre d'action. |
| `type` | "pickup" ou "quick_move" ou "clone" ou "throw" ou "swap" ou "pickup_all" ou "quick_craft" | non | "pickup" | 'pickup' clic simple, 'quick_move' equivalent maj-clic. Les autres deplacent ou jettent des objets et sont refuses sauf autorisation dans la config du mod. |
| `dryRun` | boolean | non | false | Ne rien envoyer : renvoie seulement la case visee et son contenu. |
| `waitTicks` | int | non | 5 | Ticks d'attente avant de relire l'interface, le temps que le serveur reponde. |

## studio

### `frame_target`

Methode RPC `studio.frameTarget`, action.

Photographie une entite sous un ou plusieurs angles, tout seul : calcule le cadrage a partir de son encombrement (entite, passagers et displays attaches, donc un mob ModelEngine ou un meuble Nexo entier), passe le joueur en spectateur, le teleporte a la bonne distance pour chaque angle, isole la cible sur fond uni avec eclairage plein jour, capture, puis restaure le focus, le mode de jeu, le champ de vision et la position de depart. La distance est calculee par vue pour que la cible remplisse l'image sans etre coupee, en tenant compte du format de la fenetre. Cible par uuid, id, ou type (l'entite chargee la plus proche). Le cadrage se corrige tout seul : la premiere prise garantit que rien n'est coupe, puis le sujet est mesure sur le fond uni pour rapprocher la camera (refine) et rogner l'image (autoCrop). Si le resultat ne convient pas, jouer sur margin, ou fixer distance. studio_bounds montre la mesure retenue sans consommer d'image. angles : front, back, left, right, top, bottom, iso, iso_left, three_quarter (azimut relatif a l'orientation de la cible). turntable N produit N vues reparties sur 360 degres. Maximum 12 vues par appel, chacune renvoyee comme une image : commencer par une seule vue, et utiliser studio_bounds pour verifier un cadrage sans consommer d'images. Necessite la permission de /tp et /gamemode ; sans elle, l'appel echoue avant tout deplacement. Le resultat est un resume : cible, taille mesuree, camera reelle et remplissage de chaque vue ; verbose:true renvoie le plan complet.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `uuid` | string | non |  | UUID de la cible. Fournir uuid, id ou type. |
| `id` | int | non |  | Identifiant numerique de la cible. Fournir uuid, id ou type. |
| `type` | string | non |  | Ex. 'item_display' ; prend l'entite chargee la plus proche. |
| `angles` | string[] | non |  | Vues nommees. Defaut : ['front']. |
| `customAngles` | {azimuth, pitch, name}[] | non |  | Angles explicites en degres, azimut relatif a la cible. |
| `turntable` | int | non |  | Nombre de vues reparties sur 360 degres. |
| `turntablePitch` | number | non | 15 | Inclinaison des vues du tourne-disque en degres (defaut 15, positif = vue de dessus). |
| `absoluteAzimuth` | boolean | non | false | Interpreter l'azimut comme un yaw monde plutot que relatif a la cible. |
| `attachRadius` | number | non | 4 | Rayon de prise en compte des displays attaches. |
| `boundsSource` | "auto" ou "culling" ou "hitbox" | non | "auto" | Mesure du sujet. 'auto' et 'culling' utilisent la taille declaree pour le rendu, seule mesure du modele d'un display (la hitbox d'un item_display est un point) mais souvent plus large que le modele reel. 'hitbox' s'en tient aux boites de collision. |
| `margin` | number | non | 1.15 | Air autour du sujet : 1.0 colle aux bords, en dessous rogne, au-dessus eloigne. Baisser vers 0.7 si le sujet parait petit. |
| `distance` | number | non |  | Distance camera imposee et identique pour toutes les vues ; sinon calculee par vue. |
| `fov` | number | non |  | Champ de vision applique pendant la prise de vue puis restaure (defaut 60). |
| `studio` | boolean | non | true | Isoler la cible sur fond uni ; false pour garder le decor. |
| `refine` | boolean | non | true | Regler la distance en mesurant le sujet sur le fond uni : la bonne distance est encadree par dichotomie entre une valeur ou le sujet deborde et une ou il tient entier, et l'image renvoyee est la meilleure prise non coupee. Jusqu'a quatre captures supplementaires par vue, pour un sujet en pleine resolution sans reglage manuel. |
| `autoCrop` | boolean | non | true | Rogner l'image finale sur le sujet detecte, avec une petite marge. |
| `backgroundColor` | string | non | "#202020" | Fond uni en mode studio. |
| `spectator` | boolean | non | true | Passer en spectateur pendant la prise de vue puis restaurer le mode precedent. |
| `returnToStart` | boolean | non | true | Revenir a la position de depart a la fin. |
| `waitTicks` | int | non | 6 | Ticks d'attente apres chaque deplacement (20 = 1 s). |
| `maxWidth` | int | non | 640 | Largeur maximale de chaque image renvoyee (defaut 640). |
| `format` | "jpeg" ou "png" | non | "jpeg" | 'jpeg' (defaut) ou 'png' pour un rendu fidele. |
| `quality` | number | non |  | Qualite JPEG entre 0 et 1 (defaut 0.85). Sans effet en PNG. |
| `verbose` | boolean | non | false | Renvoyer le plan complet : boites de mesure, liste des parties, passes de reglage de la distance. Par defaut, un resume bien moins couteux en tokens. |

### `studio_bounds`

Methode RPC `studio.bounds`, lecture.

Calcule l'encombrement d'une cible (boite englobante de l'entite, de ses passagers et des displays attaches, avec la liste des elements retenus) et les positions de camera pour les angles demandes, sans rien capturer, deplacer ni modifier. Memes parametres de cadrage que frame_target. A utiliser pour regler margin, distance et angles sans cout en images.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `uuid` | string | non |  | UUID de la cible. Fournir uuid, id ou type. |
| `id` | int | non |  | Identifiant numerique de la cible. Fournir uuid, id ou type. |
| `type` | string | non |  | Type d'entite ; la plus proche du joueur est retenue. |
| `angles` | string[] | non |  | Vues nommees : front, back, left, right, top, bottom, iso, iso_left, three_quarter. |
| `customAngles` | {azimuth, pitch, name}[] | non |  | Vues libres {azimuth, pitch, name?} en degres, azimut relatif a l'orientation de la cible. |
| `turntable` | int | non |  | Nombre de vues reparties sur un tour complet. |
| `turntablePitch` | number | non |  | Inclinaison des vues du tourne-disque en degres (defaut 15). |
| `absoluteAzimuth` | boolean | non |  | Traiter l'azimut comme un yaw du monde et non comme un angle relatif a la cible (defaut false). |
| `attachRadius` | number | non | 4 | Rayon en blocs ou inclure les displays attaches dans la mesure du sujet (defaut 4). |
| `boundsSource` | "auto" ou "culling" ou "hitbox" | non | "auto" | Mesure du sujet : 'auto' (defaut), 'culling' pour la taille declaree au rendu, 'hitbox' pour la boite de collision. |
| `margin` | number | non | 1.15 | Marge multiplicative autour du sujet (defaut 1.15) : au-dessus de 1, laisse de l'air. |
| `distance` | number | non |  | Distance de camera imposee en blocs, au lieu du calcul de cadrage. |
| `fov` | number | non |  | Champ de vision vertical en degres pour le calcul et la prise de vue (defaut 60). |

## reflect

### `reflect_invoke`

Methode RPC `reflect.invoke`, action.

Echappatoire : invoque n'importe quelle methode d'une classe autorisee sur le thread de rendu. Sans target, appel statique. Les objets non serialisables reviennent comme {__type:'object_ref', __id:'obj_N'} ; assignTo les stocke sous $nom. Exemple : className 'net.minecraft.client.Minecraft', methodName 'getInstance', assignTo 'mc'. Minecraft 26.x n'est pas obfusque, les noms sont ceux des sources Mojang. Utiliser get_class_info pour decouvrir une classe.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `className` | string | oui |  | Nom complet de la classe, par exemple net.minecraft.client.Minecraft. |
| `methodName` | string | oui |  | Nom de la methode a appeler. |
| `args` | {type, value}[] | non | [] | Arguments, chacun {type, value}. Une valeur '$nom' ou 'obj_N' designe une variable ou une poignee. |
| `target` | string | non |  | '$nom' ou 'obj_N' pour un appel d'instance. |
| `assignTo` | string | non |  | Nom de variable ou stocker le resultat. |

### `reflect_get_field`

Methode RPC `reflect.getField`, lecture.

Lit un champ (statique sans target, d'instance avec target). Remonte la hierarchie des classes.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `className` | string | oui |  | Nom complet de la classe qui declare le champ. |
| `fieldName` | string | oui |  | Nom du champ, meme prive. |
| `target` | string | non |  | Instance a lire, '$nom' ou 'obj_N'. Omettre pour un champ statique. |
| `assignTo` | string | non |  | Stocke le resultat dans la variable $nom, reutilisable comme target. |

### `reflect_set_field`

Methode RPC `reflect.setField`, action.

Ecrit un champ. valueType precise le type Java de la valeur (defaut : type declare du champ).

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `className` | string | oui |  | Nom complet de la classe qui declare le champ. |
| `fieldName` | string | oui |  | Nom du champ a modifier. |
| `value` | any | oui |  | Nouvelle valeur. |
| `valueType` | string | non |  | Type Java de la valeur (defaut : le type declare du champ). |
| `target` | string | non |  | Instance a modifier, '$nom' ou 'obj_N'. Omettre pour un champ statique. |

### `reflect_new_instance`

Methode RPC `reflect.newInstance`, action.

Construit un objet d'une classe autorisee, sur le thread de rendu.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `className` | string | oui |  | Nom complet de la classe a instancier. |
| `args` | {type, value}[] | non | [] | Arguments du constructeur, chacun {type, value}. |
| `assignTo` | string | non |  | Stocke l'instance creee dans la variable $nom. |

### `get_class_info`

Methode RPC `reflect.classInfo`, lecture.

Methodes et champs declares d'une classe (includeInherited pour les publics herites), avec filtre optionnel sur le nom.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `className` | string | oui |  | Nom complet de la classe a inspecter. |
| `includeInherited` | boolean | non | false | Inclure les membres herites (defaut false : seulement ceux declares par la classe). |
| `filter` | string | non |  | Sous-chaine a chercher dans les noms. |

## vars

### `var_get`

Methode RPC `vars.get`, lecture.

Valeur serialisee d'une variable $nom stockee par assignTo.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `name` | string | oui |  | Nom de la variable a lire, sans le $. |

### `var_list`

Methode RPC `vars.list`, lecture.

Noms et classes Java de toutes les variables $nom creees par assignTo. Sert a savoir ce qui est encore disponible avant de chainer un appel de reflexion.

Sans parametre.

### `var_delete`

Methode RPC `vars.delete`, action.

Supprime une variable $nom creee par assignTo. Les poignees obj_N, elles, s'effacent d'elles-memes quand la limite est atteinte.

| Parametre | Type | Requis | Defaut | Description |
|---|---|---|---|---|
| `name` | string | oui |  | Nom de la variable a supprimer, sans le $. |

### `var_clear`

Methode RPC `vars.clear`, action.

Vide toutes les variables et references d'objets de la reflexion.

Sans parametre.

