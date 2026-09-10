#!/usr/bin/env bash
# Reconstruit les deux artefacts embarques dans le plugin Claude Code :
#   - le serveur MCP, regroupe en un fichier unique sans dependances (le plugin ne fait pas npm install)
#   - le mod Fabric, pour que l'utilisateur n'ait pas besoin d'un JDK
#
# A relancer apres toute modification du mod ou du serveur MCP : ces fichiers sont versionnes et ne
# se regenerent pas seuls, or un plugin publie avec des artefacts en retard n'expose pas les tools
# qu'on vient d'ajouter, sans aucun message d'erreur. Les tests et la coherence du contrat sont
# verifies avant la construction, et le contenu des artefacts apres.
set -euo pipefail
racine="$(cd "$(dirname "$0")/.." && pwd)"
version=$(grep -oP 'mod_version=\K.*' "$racine/mod/gradle.properties")

echo "==> serveur MCP (tests, contrat, bundle)"
cd "$racine/mcp-server"
npm ci --silent
npm run build --silent
node --test test/
node "$racine/scripts/check-contract.mjs"
npx esbuild src/index.ts --bundle --platform=node --target=node20 --format=esm \
  --outfile="$racine/plugins/mcbridge/server/mcbridge-mcp.mjs"

echo "==> mod Fabric (tests et jar)"
cd "$racine/mod"
./gradlew test build -q
rm -f "$racine/plugins/mcbridge/mod/"*.jar
cp "build/libs/mcbridge-$version.jar" "$racine/plugins/mcbridge/mod/"

echo "==> versions"
python3 - "$racine" "$version" <<'PY'
import json, pathlib, sys
racine, version = pathlib.Path(sys.argv[1]), sys.argv[2]
for chemin, cle in ((racine / "plugins/mcbridge/.claude-plugin/plugin.json", "version"),):
    d = json.loads(chemin.read_text())
    d[cle] = version
    chemin.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n")
print("plugin.json aligne sur", version)
PY

echo "==> reference des tools"
cd "$racine"
node scripts/gen-tools-doc.mjs > docs/TOOLS.md

echo "==> verification des artefacts"
node scripts/check-plugin.mjs

echo "==> fait"
ls -la "$racine/plugins/mcbridge/server/" "$racine/plugins/mcbridge/mod/"
