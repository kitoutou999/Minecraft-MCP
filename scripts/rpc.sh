#!/usr/bin/env bash
# Appelle une methode RPC du bridge mcbridge sans passer par MCP.
# Usage : scripts/rpc.sh <methode> ['<params json>']
#   scripts/rpc.sh info.status
#   scripts/rpc.sh chat.send '{"message":"/time set noon"}'
# Variables : MCBRIDGE_URL (defaut http://127.0.0.1:25580), MCBRIDGE_TOKEN ou MCBRIDGE_CONFIG.
set -euo pipefail
METHOD="${1:-info.status}"
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
if [ -z "$TOKEN" ]; then
  echo "jeton introuvable : definir MCBRIDGE_TOKEN ou MCBRIDGE_CONFIG ($CONFIG absent)" >&2
  exit 1
fi
BODY=$(python3 -c "import json,sys; print(json.dumps({'method': sys.argv[1], 'params': json.loads(sys.argv[2])}))" "$METHOD" "$PARAMS")
curl -sS -X POST "$URL/rpc" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data "$BODY" | python3 -m json.tool
