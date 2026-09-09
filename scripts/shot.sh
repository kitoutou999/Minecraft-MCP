#!/usr/bin/env bash
# Prend un screenshot via le bridge et l'ecrit sur disque.
# Usage : scripts/shot.sh [fichier de sortie] ['<params json>']
#   scripts/shot.sh /tmp/capture.jpg
#   scripts/shot.sh /tmp/capture.png '{"format":"png","maxWidth":0,"teleport":{"x":100,"y":80,"z":100,"yaw":0,"pitch":20}}'
set -euo pipefail
OUT="${1:-/tmp/mcbridge-capture.jpg}"
PARAMS="${2:-{\}}"
URL="${MCBRIDGE_URL:-http://127.0.0.1:25580}"
CONFIG="${MCBRIDGE_CONFIG:-}"
if [ -z "$CONFIG" ]; then
  # Decouverte : .minecraft puis profils Modrinth App, le plus recent en premier
  CONFIG=$(ls -t "$HOME/.minecraft/config/mcbridge.json" "$HOME"/.local/share/ModrinthApp/profiles/*/config/mcbridge.json \
    "$HOME"/snap/modrinth/common/.local/share/ModrinthApp/profiles/*/config/mcbridge.json \
    "$HOME"/.config/com.modrinth.theseus/profiles/*/config/mcbridge.json 2>/dev/null | head -1 || true)
fi
TOKEN="${MCBRIDGE_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$CONFIG" ]; then
  TOKEN=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('token',''))" "$CONFIG")
fi
BODY=$(python3 -c "import json,sys; print(json.dumps({'method': 'vision.screenshot', 'params': json.loads(sys.argv[1])}))" "$PARAMS")
curl -sS -X POST "$URL/rpc" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" --data "$BODY" \
  | python3 -c "
import json, sys, base64
env = json.load(sys.stdin)
if not env.get('ok'):
    print('erreur :', env.get('error'), file=sys.stderr); sys.exit(1)
r = env['result']
open(sys.argv[1], 'wb').write(base64.b64decode(r['base64']))
print(f\"{sys.argv[1]} : {r['width']}x{r['height']} {r['format']} {r['bytes']} octets, capture {r.get('capture')}\")
" "$OUT"
