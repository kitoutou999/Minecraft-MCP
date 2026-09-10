// Verifie que les artefacts embarques dans le plugin Claude Code correspondent bien aux sources.
//
// Un plugin est copie tel quel chez l'utilisateur, sans etape de build : le jar du mod et le bundle
// du serveur MCP sont donc versionnes. Rien ne les regenere tout seuls, et un plugin publie avec des
// artefacts en retard n'expose pas les tools qu'on vient d'ajouter, sans le moindre message
// d'erreur. Ce script compare le contenu, pas les dates : un clone git ne conserve pas les dates.
//
// Usage : node scripts/check-plugin.mjs   (apres scripts/build-plugin.sh)
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, join, relative } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const pluginDir = join(root, "plugins", "mcbridge");
const problems = [];

const version = /mod_version=(.+)/.exec(readFileSync(join(root, "mod", "gradle.properties"), "utf8"))?.[1].trim();

// --- le bundle expose-t-il tous les tools du catalogue ? ------------------------------------
const bundle = join(pluginDir, "server", "mcbridge-mcp.mjs");
const catalogue = join(root, "mcp-server", "dist", "tools.js");
if (!existsSync(bundle)) problems.push(`bundle absent : ${relative(root, bundle)}`);
else if (!existsSync(catalogue)) problems.push("catalogue non compile : cd mcp-server && npm run build");
else {
  const { TOOLS } = await import(pathToFileURL(catalogue).href);
  const code = readFileSync(bundle, "utf8");
  const missing = TOOLS.filter((t) => !code.includes(`"${t.name}"`)).map((t) => t.name);
  if (missing.length > 0) {
    problems.push(`le bundle du plugin ne contient pas ${missing.length} tool(s) : ${missing.join(", ")}` +
      "\n   relancer scripts/build-plugin.sh");
  }
}

// --- le jar contient-il toutes les classes du mod ? ------------------------------------------
const jar = join(pluginDir, "mod", `mcbridge-${version}.jar`);
if (!existsSync(jar)) {
  problems.push(`jar absent ou de mauvaise version : ${relative(root, jar)} (mod_version=${version})`);
} else {
  const entries = new Set(execFileSync("unzip", ["-Z1", jar], { encoding: "utf8" }).split("\n"));
  const sources = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (entry.endsWith(".java")) sources.push(path);
    }
  };
  walk(join(root, "mod", "src", "main", "java"));
  const missing = sources
    .map((path) => relative(join(root, "mod", "src", "main", "java"), path).replace(/\.java$/, ".class"))
    .filter((cls) => !entries.has(cls));
  if (missing.length > 0) {
    problems.push(`le jar du plugin ne contient pas ${missing.length} classe(s), dont ${missing.slice(0, 3).join(", ")}` +
      "\n   relancer scripts/build-plugin.sh");
  }
}

// --- la version annoncee par le plugin suit-elle celle du mod ? ------------------------------
const manifest = JSON.parse(readFileSync(join(pluginDir, ".claude-plugin", "plugin.json"), "utf8"));
if (manifest.version !== version) {
  problems.push(`plugin.json annonce ${manifest.version} alors que le mod est en ${version}`);
}

if (problems.length > 0) {
  console.error(`artefacts du plugin en retard : ${problems.length} probleme(s)`);
  for (const p of problems) console.error(` - ${p}`);
  process.exit(1);
}
console.log(`artefacts du plugin a jour : mcbridge-${version}.jar et le bundle contiennent les sources courantes`);
