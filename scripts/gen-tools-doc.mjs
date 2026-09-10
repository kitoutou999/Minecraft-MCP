// Genere docs/TOOLS.md a partir du catalogue compile mcp-server/dist/tools.js.
// Usage : cd mcp-server && npm run build && cd .. && node scripts/gen-tools-doc.mjs > docs/TOOLS.md
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const { TOOLS } = await import(pathToFileURL(join(here, "..", "mcp-server", "dist", "tools.js")).href);

function unwrap(schema) {
  let s = schema;
  let optional = false;
  let def;
  for (;;) {
    const t = s._def?.typeName;
    if (t === "ZodOptional") { optional = true; s = s._def.innerType; continue; }
    if (t === "ZodDefault") { optional = true; def = s._def.defaultValue(); s = s._def.innerType; continue; }
    break;
  }
  return { inner: s, optional, def };
}

function typeName(schema) {
  const { inner } = unwrap(schema);
  const t = inner._def?.typeName;
  switch (t) {
    case "ZodString": return "string";
    case "ZodNumber": return inner._def.checks?.some((c) => c.kind === "int") ? "int" : "number";
    case "ZodBoolean": return "boolean";
    case "ZodArray": return `${typeName(inner._def.type)}[]`;
    case "ZodEnum": return inner._def.values.map((v) => `"${v}"`).join(" ou ");
    case "ZodObject": return `{${Object.keys(inner.shape).join(", ")}}`;
    case "ZodUnknown": return "any";
    default: return t ?? "?";
  }
}

const groups = new Map();
for (const t of TOOLS) {
  const g = t.method.split(".")[0];
  if (!groups.has(g)) groups.set(g, []);
  groups.get(g).push(t);
}

const out = [];
out.push("# Reference des tools");
out.push("");
out.push("Fichier genere par `scripts/gen-tools-doc.mjs` depuis `mcp-server/src/tools.ts`. Ne pas editer a la main.");
out.push("");
out.push(`${TOOLS.length} tools. Convention : yaw 0 = sud, 90 = ouest, -90 = est, 180 = nord ; pitch -90 = haut, 90 = bas ; 20 ticks = 1 s.`);
out.push("");
for (const [g, tools] of groups) {
  out.push(`## ${g}`);
  out.push("");
  for (const t of tools) {
    const ro = t.annotations?.readOnlyHint ? "lecture" : "action";
    out.push(`### \`${t.name}\``);
    out.push("");
    if (t.local) out.push("Compose par le serveur MCP, sans methode RPC dans le mod, action.");
    else out.push(`Methode RPC \`${t.method}\`, ${ro}${t.kind === "image" ? ", renvoie une image" : ""}.`);
    out.push("");
    out.push(t.description);
    out.push("");
    const keys = Object.keys(t.inputSchema);
    if (keys.length === 0) {
      out.push("Sans parametre.");
    } else {
      out.push("| Parametre | Type | Requis | Defaut | Description |");
      out.push("|---|---|---|---|---|");
      for (const k of keys) {
        const s = t.inputSchema[k];
        const { optional, def } = unwrap(s);
        const desc = (s.description ?? unwrap(s).inner.description ?? "").replace(/\|/g, "\\|");
        out.push(`| \`${k}\` | ${typeName(s)} | ${optional ? "non" : "oui"} | ${def === undefined ? "" : JSON.stringify(def)} | ${desc} |`);
      }
    }
    out.push("");
  }
}
console.log(out.join("\n"));
