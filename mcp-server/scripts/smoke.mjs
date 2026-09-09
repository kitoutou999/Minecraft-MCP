// Test de fumee : demarre le serveur MCP compile en stdio, liste les tools et verifie qu'un appel
// sans Minecraft renvoie une erreur "bridge injoignable" propre (et non un crash).
// Usage : npm run build && npm run smoke
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [join(here, "..", "dist", "index.js")],
  env: { ...process.env, MCBRIDGE_URL: "http://127.0.0.1:1", MCBRIDGE_TOKEN: "smoke", MCBRIDGE_TIMEOUT_MS: "1500" },
  stderr: "pipe",
});
const client = new Client({ name: "smoke", version: "0.0.0" });
await client.connect(transport);

const { tools } = await client.listTools();
console.log(`tools enregistres : ${tools.length}`);
for (const t of tools) console.log(` - ${t.name}`);
if (tools.length < 20) throw new Error("trop peu de tools");

const res = await client.callTool({ name: "get_status", arguments: {} });
const text = res.content?.[0]?.text ?? "";
console.log(`get_status sans Minecraft -> isError=${res.isError} : ${text.slice(0, 90)}...`);
if (!res.isError || !text.includes("injoignable")) throw new Error("l'erreur bridge injoignable n'est pas propagee correctement");

await client.close();
console.log("smoke OK");
