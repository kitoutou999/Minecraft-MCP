// Verifie que le catalogue MCP et les handlers du mod decrivent bien la meme chose.
//
// Le contrat vit en deux endroits : les methodes enregistrees en Java et les entrees de
// mcp-server/src/tools.ts. Rien dans le langage ne les relie, donc une methode renommee d'un cote
// ne se voit qu'a l'execution, sous la forme d'un "Methode inconnue" au pire moment. Ce script
// rapproche les deux listes et verifie au passage ce que la checklist de CLAUDE.md demande :
// description utile, parametres decrits, coherence des annotations.
//
// Usage : cd mcp-server && npm run build && cd .. && node scripts/check-contract.mjs
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const modSources = join(root, "mod", "src", "main", "java");
const catalogue = join(root, "mcp-server", "dist", "tools.js");

const problems = [];
const note = (message) => problems.push(message);

// --- cote mod : methodes enregistrees dans le routeur ---------------------------------------
function javaFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...javaFiles(path));
    else if (entry.endsWith(".java")) out.push(path);
  }
  return out;
}

const javaMethods = new Map(); // methode -> { exclusive, file }
for (const file of javaFiles(modSources)) {
  const source = readFileSync(file, "utf8");
  for (const m of source.matchAll(/router\.register(Exclusive)?\(\s*"([a-zA-Z][\w.]*)"/g)) {
    const [, exclusive, method] = m;
    if (javaMethods.has(method)) note(`methode RPC enregistree deux fois : ${method}`);
    javaMethods.set(method, { exclusive: Boolean(exclusive), file: file.slice(root.length + 1) });
  }
}
if (javaMethods.size === 0) note("aucune methode RPC trouvee dans les sources du mod");

// --- cote serveur MCP : catalogue compile ----------------------------------------------------
if (!existsSync(catalogue)) {
  console.error(`catalogue introuvable : ${catalogue}\nCompiler d'abord : cd mcp-server && npm run build`);
  process.exit(2);
}
const { TOOLS } = await import(pathToFileURL(catalogue).href);

/** Retire les enveloppes optional/default pour atteindre le schema reel. */
function unwrap(schema) {
  let s = schema;
  const descriptions = [];
  for (;;) {
    if (s?._def?.description) descriptions.push(s._def.description);
    const t = s?._def?.typeName;
    if (t === "ZodOptional" || t === "ZodDefault" || t === "ZodNullable") {
      s = s._def.innerType;
      continue;
    }
    break;
  }
  return { inner: s, described: descriptions.length > 0 };
}

const seenNames = new Set();
const seenMethods = new Set();
let localCount = 0;
for (const tool of TOOLS) {
  const where = `tool ${tool.name}`;
  if (seenNames.has(tool.name)) note(`nom de tool en double : ${tool.name}`);
  seenNames.add(tool.name);
  if (!tool.local) seenMethods.add(tool.method);

  if (!/^[a-z][a-z0-9_]*$/.test(tool.name)) note(`${where} : le nom doit etre en snake_case`);
  if (!/^[a-z][a-zA-Z]*\.[a-zA-Z][a-zA-Z]*$/.test(tool.method)) {
    note(`${where} : la methode "${tool.method}" doit s'ecrire espace.action en camelCase`);
  }
  if (!tool.title) note(`${where} : titre manquant`);
  if (!tool.description || tool.description.length < 40) {
    note(`${where} : description trop courte, c'est ce que lit le modele pour choisir`);
  }
  if (tool.kind && !["json", "image", "images", "diff", "diffs"].includes(tool.kind)) {
    note(`${where} : kind inconnu "${tool.kind}"`);
  }
  for (const [param, schema] of Object.entries(tool.inputSchema ?? {})) {
    if (!unwrap(schema).described) note(`${where} : le parametre "${param}" n'a pas de .describe()`);
  }

  // Un tool compose n'a pas de handler Java : le serveur MCP enchaine lui-meme d'autres tools, et
  // porte donc les effets de chacun d'eux.
  if (tool.local) {
    localCount++;
    if (tool.annotations?.readOnlyHint) note(`${where} : un tool compose enchaine des actions, il ne peut pas etre readOnly`);
    continue;
  }

  const java = javaMethods.get(tool.method);
  if (!java) {
    note(`${where} : la methode "${tool.method}" n'est enregistree par aucun handler du mod`);
    continue;
  }
  // Une action qui prend le controle du client ne doit pas etre annoncee sans effet : le modele
  // la croirait libre de consequence et l'enchainerait au milieu d'une prise de vue.
  if (java.exclusive && tool.annotations?.readOnlyHint) {
    note(`${where} : "${tool.method}" est exclusive cote mod mais annoncee readOnlyHint`);
  }
}

for (const [method, info] of javaMethods) {
  if (!seenMethods.has(method)) {
    note(`methode "${method}" (${info.file}) exposee par le mod mais absente du catalogue MCP`);
  }
}

// --- verdict ---------------------------------------------------------------------------------
const exclusiveCount = [...javaMethods.values()].filter((m) => m.exclusive).length;
if (problems.length > 0) {
  console.error(`contrat incoherent : ${problems.length} probleme(s)`);
  for (const p of problems) console.error(` - ${p}`);
  process.exit(1);
}
console.log(`contrat OK : ${TOOLS.length} tools (dont ${localCount} compose(s) cote serveur), ` +
  `${javaMethods.size} methodes RPC (${exclusiveCount} exclusives), parametres tous decrits`);
