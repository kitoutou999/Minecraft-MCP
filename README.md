# mcbridge : un pont entre Claude et un client Minecraft

Donne à une IA les yeux et les mains d'un joueur Minecraft, pour qu'elle vérifie elle-même ce
qu'elle développe : se placer, cadrer, capturer, lire un menu, comparer, corriger.

Là où les autres ponts Minecraft pilotent un bot sans écran, celui-ci passe par un **mod client**.
Il voit donc exactement ce que voit le joueur, shaders compris, et peut photographier un modèle
isolé sur fond uni comme dans un studio.

```
Claude Code, Claude Desktop, ou tout client MCP
        │  MCP (stdio ou HTTP)
   serveur MCP  (Node 20, TypeScript)                    mcp-server/
        │  HTTP local, jeton bearer, 127.0.0.1 uniquement
   mod Fabric « mcbridge »  (dans le client Minecraft)   mod/
        │  tout accès au jeu passe par le fil de rendu
   vision · focus · studio · interfaces · références · monde · réflexion
```

## Ce que ça permet

| Situation | Ce que l'IA peut faire seule |
|---|---|
| Un modèle 3D vient d'être posé en jeu | Le photographier isolé sur fond uni, sous plusieurs angles, cadrage calculé |
| Un lore ne rend pas comme prévu | Lire son texte exact, segment par segment, avec la couleur et le style de chacun |
| Un menu de plugin a dix pages | Les parcourir en cliquant, et lire chaque page |
| Une animation doit être jugée | Recevoir une planche de vues successives plutôt qu'une image figée |
| Le pack de ressources vient de changer | Recharger, lire les erreurs du client, comparer à des images de référence |
| Une texture est mal placée | Isoler la zone, masquer le décor, agrandir la case concernée |

## Deux morceaux, et une dépendance

Le mod et le serveur MCP vont ensemble. **Le serveur MCP seul ne sert à rien** : il ne fait que
relayer vers le mod, qui doit tourner dans un client Minecraft connecté. Sans lui, chaque appel
répond « bridge injoignable ».

| Prérequis | Version |
|---|---|
| Minecraft | 26.1.2, avec Fabric Loader 0.19.3 ou plus et Fabric API |
| JDK | 25, pour compiler le mod (Minecraft 26.x est compilé en Java 25) |
| Node.js | 20 ou plus récent, pour le serveur MCP |
| Gradle | le wrapper est fourni, rien à installer |

## Installation

### 1. Le mod, dans le client

```bash
cd mod
./gradlew build        # produit build/libs/mcbridge-0.1.0.jar
./gradlew installMod   # copie le jar dans ~/.minecraft/mods
```

Pour un lanceur à profils, indiquez le dossier de mods du profil :

```bash
PROFIL=~/.local/share/ModrinthApp/profiles/MonProfil
MCBRIDGE_MODS_DIR=$PROFIL/mods ./gradlew installMod
```

Lancez Minecraft une fois. Le mod crée `config/mcbridge.json` avec un jeton et affiche dans le
journal l'adresse du pont, par exemple `[mcbridge] prêt : bridge http://127.0.0.1:25580`.

### 2. Le serveur MCP

```bash
cd mcp-server
npm ci
npm run build
npm run smoke     # vérifie l'enregistrement des outils, sans Minecraft
```

### 3. Le brancher à Claude Code

Le jeton est lu directement dans la configuration du mod, rien à recopier :

```bash
claude mcp add mcbridge -s user \
  -e MCBRIDGE_CONFIG=$HOME/.minecraft/config/mcbridge.json \
  -- node $PWD/dist/index.js
```

Le serveur MCP retrouve seul la configuration dans `~/.minecraft`, dans les profils de Modrinth App
(y compris l'installation snap) et dans les instances de Prism. La variable n'est utile que si la
découverte échoue ou si plusieurs profils coexistent.

Pour Claude Desktop, voir `examples/mcp.json`.

### Le client sur une autre machine

Dans `config/mcbridge.json` du client, mettre `host` à `0.0.0.0` et `allowRemote` à vrai, ouvrir le
port, puis côté MCP donner `MCBRIDGE_URL=http://<adresse du client>:25580` et `MCBRIDGE_TOKEN`. Le
jeton reste obligatoire.

## Les 48 outils

| Groupe | Outils |
|---|---|
| État | `get_status`, `get_player` |
| Caméra | `get_camera`, `look`, `look_at` |
| Vision | `screenshot`, `describe_scene`, `capture_animation` |
| Focus | `focus_entities`, `focus_scene`, `focus_region`, `focus_clear`, `focus_status` |
| Studio | `frame_target`, `studio_bounds` |
| Interfaces | `get_gui`, `open_inventory`, `close_gui`, `get_item_lore`, `hover_slot`, `screenshot_gui`, `click_slot` |
| Références | `save_reference`, `compare_reference`, `compare_all_references`, `list_references`, `delete_reference` |
| Monde | `get_block`, `list_entities`, `get_entity` |
| Chat et commandes | `send_command`, `send_chat`, `get_chat`, `poll_events` |
| Client | `get_client_options`, `set_client_options`, `reload_resource_pack`, `get_client_log`, `wait_ticks` |
| Réflexion | `reflect_invoke`, `reflect_get_field`, `reflect_set_field`, `reflect_new_instance`, `get_class_info`, `var_get`, `var_list`, `var_delete`, `var_clear` |

Référence complète avec tous les paramètres : [docs/TOOLS.md](docs/TOOLS.md), fichier généré depuis
le catalogue.

## Trois façons de s'en servir

**Photographier une entité.** Un seul appel suffit : `frame_target` avec l'identifiant de la cible.
Il mesure le sujet, passe en spectateur, se place, isole la cible sur fond uni avec un éclairage
plein jour, corrige la distance en mesurant le sujet dans l'image, puis remet le mode de jeu, la
position et les réglages en place.

**Vérifier un objet et son lore.** `open_inventory` ou la commande du menu, puis `get_gui` pour
repérer la case, `get_item_lore` pour le texte exact avec ses couleurs, et `screenshot_gui` si le
rendu lui-même est en cause. `click_slot` change de page.

**Surveiller les régressions visuelles.** `save_reference` une fois sur chaque élément à surveiller,
avec son sujet isolé. Après une modification du pack, `compare_all_references` donne un verdict
chiffré pour chacun, sans renvoyer d'image tant que rien n'a bougé.

## Sans MCP

Deux scripts appellent le pont directement, pratiques pour tester ou déboguer :

```bash
scripts/rpc.sh info.status
scripts/rpc.sh chat.send '{"message":"/time set noon"}'
scripts/shot.sh /tmp/capture.jpg
```

## Configuration du mod

Fichier `config/mcbridge.json` du client.

| Clé | Défaut | Rôle |
|---|---|---|
| `host`, `port` | `127.0.0.1`, `25580` | Adresse du pont. Ne pas exposer sans comprendre le risque. |
| `token`, `requireAuth` | généré, `true` | Jeton bearer obligatoire. |
| `allowRemote` | `false` | Refuse toute connexion non locale, même si `host` change. |
| `commandMinIntervalMs` | `1100` | Intervalle minimal entre deux commandes. Un serveur déconnecte pour spam au-delà d'une dizaine de commandes rapprochées. |
| `preferClientTeleport` | `true` | En spectateur, déplacer la caméra sans commande. |
| `teleportCommand`, `gamemodeCommand` | `minecraft:tp`, `minecraft:gamemode` | Forme qualifiée, pour contourner les plugins qui redéfinissent ces commandes. |
| `enableVision`, `enableCommands`, `enableClientOptions`, `enableFocus`, `enableReflection` | `true` | Portes de capacités. |
| `enableGuiClicks` | `false` | Autorise le clic dans un menu, seule action qui modifie l'état du serveur. |
| `screenshot.*` | jpeg, 1280 px | Format et taille par défaut des captures. |
| `reflection.*` | voir le fichier | Classes accessibles par réflexion. |

## Sécurité

Le pont écoute en local et exige un jeton comparé en temps constant. Il refuse les connexions non
locales même si l'adresse d'écoute change.

Le mod **observe et pilote le client**, il ne touche pas au serveur, à deux exceptions près,
toutes deux explicites : les commandes envoyées en tant que joueur, avec les permissions de ce
joueur, et le clic dans un menu, désactivé par défaut. Un clic part vraiment au serveur : dans une
boutique, il achète.

La réflexion donne accès à tout le client Java. L'exécution de processus, l'accès aux fichiers et au
réseau sont bloqués par défaut, et la capacité entière se désactive d'une ligne.

## Compatibilité

Développé et validé avec Fabric API, Sodium, Iris, Voxy, Litematica sur un serveur Paper 26.1.2
avec Nexo, ModelEngine, MythicMobs, BetterHud et EssentialsX.

Le masquage du terrain coupe le point commun à Minecraft et à Sodium, et un mixin optionnel coupe le
terrain lointain de Voxy. Iris sans pack de shaders se comporte comme le jeu de base ; avec un pack
actif, ciel et brouillard lui appartiennent, donc désactivez les shaders pour le mode studio.

## Structure

```
mod/          mod Fabric client (Java 25)
mcp-server/   serveur MCP TypeScript, catalogue dans src/tools.ts
docs/         architecture, protocole, référence des outils, feuille de route
scripts/      appel direct du pont, capture, génération de la documentation
examples/     configuration pour Claude Desktop
CLAUDE.md     consignes pour les IA qui font évoluer ce dépôt
```

## Feuille de route

Le socle, le focus, le studio, les interfaces, l'animation et les références sont faits et validés
en jeu. La suite est décrite dans [docs/ROADMAP.md](docs/ROADMAP.md) : un vrai rendu hors écran, qui
permettrait des images à résolution libre et à fond transparent sans déplacer le joueur.

## Licence

MIT. Inspiré de [mcpfabric](https://github.com/Etoryx/mcpfabric) et de
[minecraft-mcp](https://github.com/InventivetalentDev/minecraft-mcp), tous deux sous MIT, dont
certaines idées de structure ont été reprises et adaptées. Détail dans `LICENSE`.
