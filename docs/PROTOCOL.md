# Protocole du bridge

Le mod expose un petit serveur HTTP sur `http://127.0.0.1:25580` (configurable).

## Authentification

Toutes les routes sauf `/health` exigent `Authorization: Bearer <token>`, le jeton etant dans
`config/mcbridge.json` du client. Les connexions dont l'adresse distante n'est pas locale sont
refusees (403) sauf `allowRemote: true`.

## Routes

| Route | Description |
|---|---|
| `GET /health` | `{ok, name, version}`, sans auth |
| `GET /info` | resultat de `info.status` |
| `POST /rpc` | corps `{"method": "espace.action", "params": {...}}` |
| `GET /events?types=chat,join` | flux SSE, un evenement JSON par ligne `data:` |

## Enveloppe de reponse

```json
{"ok": true, "result": { ... }}
{"ok": false, "error": {"code": "no_player", "message": "Le joueur n'est pas dans un monde ...", "data": null}}
```

Codes d'erreur : `bad_request`, `no_player`, `unavailable`, `forbidden`, `not_found`, `timeout`,
`interrupted`, `internal`, `unknown_method`, `unauthorized`, `method_not_allowed`, `busy`,
`rcon_failed`, `teleport_failed`.

## Actions exclusives

Une methode marquee exclusive dans la table ci-dessous prend le controle du client le temps de son
execution : elle deplace la camera, change le mode de jeu, masque le decor ou force le champ de
vision, puis restaure tout. Deux de ces appels en parallele se marcheraient dessus, la restauration
du premier effacant le reglage du second. Le bridge les serialise donc avec un verrou unique.

Un appel qui n'obtient pas le verrou en `busyTimeoutMs` (30 s par defaut) recoit :

```json
{"ok": false, "error": {"code": "busy", "message": "Le client est deja occupe par 'studio.frameTarget' depuis 12 s. ..."}}
```

Les lectures ne prennent jamais ce verrou : `info.status` reste consultable pendant une prise de
vue de plusieurs dizaines de secondes.

## Methodes RPC

| Methode | Tool MCP | Role | Exclusive |
|---|---|---|---|
| `info.status` | `get_status` | Etat du bridge et du client |  |
| `player.getState` | `get_player` | Etat du joueur local |  |
| `camera.get` | `get_camera` | Position de la camera |  |
| `camera.look` | `look` | Orienter la vue | oui |
| `camera.lookAt` | `look_at` | Regarder un point | oui |
| `vision.burst` | `capture_animation` | Capturer un mouvement | oui |
| `vision.describeScene` | `describe_scene` | Decrire la scene |  |
| `vision.screenshot` | `screenshot` | Capturer l'ecran | oui |
| `world.entitiesNearby` | `list_entities` | Lister les entites proches |  |
| `world.getBlock` | `get_block` | Lire un bloc |  |
| `world.getEntity` | `get_entity` | Detail d'une entite |  |
| `chat.recent` | `get_chat` | Lire le chat recent |  |
| `chat.send` | `send_command`, `send_chat` | executer une commande / envoyer un message | oui |
| `events.poll` | `poll_events` | Lire les evenements |  |
| `client.getOptions` | `get_client_options` | Lire les options client |  |
| `client.setOptions` | `set_client_options` | Modifier les options client | oui |
| `resources.reload` | `reload_resource_pack` | Recharger les ressources (F3+T) | oui |
| `logs.client` | `get_client_log` | Lire le log client |  |
| `game.waitTicks` | `wait_ticks` | Attendre des ticks |  |
| `focus.clear` | `focus_clear` | Desactiver le focus | oui |
| `focus.region` | `focus_region` | Isoler une zone de blocs | oui |
| `focus.set` | `focus_entities`, `focus_scene` | isoler des entites au rendu / masquer le decor (studio) | oui |
| `focus.status` | `focus_status` | Etat du focus |  |
| `gui.click` | `click_slot` | Cliquer une case | oui |
| `gui.close` | `close_gui` | Fermer l'interface | oui |
| `gui.hover` | `hover_slot` | Survoler une case | oui |
| `gui.open` | `open_inventory` | Ouvrir l'inventaire du joueur | oui |
| `gui.screenshot` | `screenshot_gui` | Photographier une interface | oui |
| `gui.state` | `get_gui` | Lire l'interface ouverte |  |
| `gui.tooltip` | `get_item_lore` | Lire l'infobulle d'un objet |  |
| `studio.bounds` | `studio_bounds` | Verifier un cadrage sans capturer |  |
| `studio.frameTarget` | `frame_target` | Photographier une entite (studio) | oui |
| `refs.compare` | `compare_reference` | Comparer une reference | oui |
| `refs.compareAll` | `compare_all_references` | Verifier toutes les references | oui |
| `refs.delete` | `delete_reference` | Supprimer une reference |  |
| `refs.list` | `list_references` | Lister les references |  |
| `refs.save` | `save_reference` | Enregistrer une image de reference | oui |
| `server.command` | `server_command` | Commande console (RCON) |  |
| `server.status` | `server_status` | Etat du serveur (RCON) |  |
| `reflect.classInfo` | `get_class_info` | Inspecter une classe Java |  |
| `reflect.getField` | `reflect_get_field` | Lire un champ Java (client) |  |
| `reflect.invoke` | `reflect_invoke` | Appeler une methode Java (client) |  |
| `reflect.newInstance` | `reflect_new_instance` | Instancier une classe Java (client) |  |
| `reflect.setField` | `reflect_set_field` | Modifier un champ Java (client) |  |
| `vars.clear` | `var_clear` | Effacer les variables et poignees |  |
| `vars.delete` | `var_delete` | Supprimer une variable de reflexion |  |
| `vars.get` | `var_get` | Lire une variable de reflexion |  |
| `vars.list` | `var_list` | Lister les variables de reflexion |  |

Le tool `run_steps` n'apparait pas dans cette table : il n'a pas de methode RPC. Le serveur MCP
enchaine lui-meme les tools qu'on lui donne, en appelant leurs methodes une par une, et renvoie tous
les resultats en une reponse. Il fait gagner des tours au client MCP, pas des appels au bridge.

## Format d'une image

`vision.screenshot` renvoie `{format, mimeType, width, height, sourceWidth, sourceHeight, bytes,
base64, capture:{playerPos, yaw, pitch, tick, hudHidden}}`. Le serveur MCP convertit `base64` en
bloc `image` MCP.

`studio.frameTarget` renvoie par defaut un resume : `{target:{id, uuid, type, name, pos}, partCount,
size, center, fov, shots:[{angle, camera:{pos, yaw, pitch, distance}, fill, format, width, height,
bytes, base64}], previousGameMode?, returnedToStart, captured}`. La camera est celle reellement
utilisee apres reglage de la distance. Avec `verbose:true`, le plan complet de `studio.bounds` plus,
par vue, `camera` (plan), `cameraUsed`, `distanceUsed`, `refineFill` et les passes `refine`.

`refs.compare` renvoie les mesures (`pixelsDiffering`, `percentDiffering`, `maxDelta`,
`differenceBox` dans le cadre complet) et, a partir de `diffImageMinPercent` de pixels differents,
l'image des differences recadree sur la zone touchee et reduite a `diffMaxWidth` : `diffBase64`,
`diffWidth`, `diffHeight`, `diffCrop` (zone gardee, en pixels de la reference). En dessous du seuil,
`diffImageOmitted` en donne la raison. `refs.compareAll` porte les memes champs par reference quand
`includeDiffImages` est vrai.

## Exemples curl

```bash
TOKEN=$(python3 -c "import json;print(json.load(open('$HOME/.minecraft/config/mcbridge.json'))['token'])")
curl -s http://127.0.0.1:25580/health
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25580/info
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"method":"camera.lookAt","params":{"x":0,"y":64,"z":0}}' http://127.0.0.1:25580/rpc
curl -N -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:25580/events?types=chat'
```
