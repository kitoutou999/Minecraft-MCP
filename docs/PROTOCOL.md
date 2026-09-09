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
`interrupted`, `internal`, `unknown_method`, `unauthorized`, `method_not_allowed`.

## Methodes RPC

| Methode | Tool MCP | Role |
|---|---|---|
| `info.status` | `get_status` | etat du bridge, capacites, liste des methodes |
| `player.getState` | `get_player` | etat du joueur local |
| `camera.get` | `get_camera` | position et orientation de la camera |
| `camera.look` | `look` | yaw/pitch absolus ou relatifs |
| `camera.lookAt` | `look_at` | viser un point |
| `vision.screenshot` | `screenshot` | capture (teleport, look, hideHud, waitTicks, maxWidth, format, quality) |
| `vision.describeScene` | `describe_scene` | cible du reticule et entites proches |
| `world.getBlock` | `get_block` | bloc a une position |
| `world.entitiesNearby` | `list_entities` | entites dans un rayon |
| `world.getEntity` | `get_entity` | detail, boite englobante, passagers, displays attaches |
| `chat.send` | `send_command`, `send_chat` | message ou commande (`/`) en tant que joueur |
| `chat.recent` | `get_chat` | historique recu |
| `events.poll` | `poll_events` | evenements depuis `sinceId` |
| `client.getOptions` | `get_client_options` | hideGui, fov, guiScale, renderDistance, gamma, fenetre |
| `client.setOptions` | `set_client_options` | modification partielle |
| `resources.reload` | `reload_resource_pack` | F3+T |
| `logs.client` | `get_client_log` | tail filtre de logs/latest.log |
| `game.waitTicks` | `wait_ticks` | attente en ticks |
| `focus.set` / `focus.clear` / `focus.status` | `focus_*` | mode focus |
| `reflect.invoke` / `getField` / `setField` / `newInstance` / `classInfo` | `reflect_*`, `get_class_info` | reflexion |
| `vars.get` / `list` / `delete` / `clear` | `var_*` | variables de reflexion |

## Format d'une image

`vision.screenshot` renvoie `{format, mimeType, width, height, sourceWidth, sourceHeight, bytes,
base64, capture:{playerPos, yaw, pitch, tick, hudHidden}}`. Le serveur MCP convertit `base64` en
bloc `image` MCP.

## Exemples curl

```bash
TOKEN=$(python3 -c "import json;print(json.load(open('$HOME/.minecraft/config/mcbridge.json'))['token'])")
curl -s http://127.0.0.1:25580/health
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25580/info
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"method":"camera.lookAt","params":{"x":0,"y":64,"z":0}}' http://127.0.0.1:25580/rpc
curl -N -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:25580/events?types=chat'
```
