#!/usr/bin/env node
/**
 * Point d'entree du serveur MCP mcbridge.
 *
 * Enregistre chaque tool du catalogue (`tools.ts`) comme un simple relais vers le bridge HTTP du
 * mod, puis sert le tout en stdio (defaut) ou en HTTP streamable. Tous les logs vont sur stderr :
 * stdout est reserve au flux JSON-RPC du transport stdio.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import http from "node:http";
import { randomUUID } from "node:crypto";

import { loadConfig, type ServerConfig } from "./config.js";
import { BridgeClient } from "./bridge.js";
import { TOOLS, type ToolDef } from "./tools.js";
import { convertResult, errorResult } from "./results.js";
import { RUN_STEPS, runSteps, type Invoke } from "./steps.js";

const PKG_VERSION = "0.1.0";

const INSTRUCTIONS =
  "Pont vers un client Minecraft (Fabric 26.1.2) via le mod mcbridge. Appeler get_status en premier. " +
  "Le mod ne controle que le client : rendu, camera, screenshots, focus, options, reflexion. " +
  "Le serveur de jeu se pilote par commandes (send_command, en tant que joueur) ou par RCON hors de ce MCP. " +
  "Pour photographier une entite precise, frame_target fait tout seul : cadrage, placement, fond uni, capture, restauration. " +
  "Pour un objet d'inventaire : open_inventory ou la commande du menu, puis get_gui pour reperer la case, get_item_lore pour le texte exact, " +
  "screenshot_gui pour l'image (crop 'slot' pour la texture, hoverSlot + crop 'tooltip' pour le lore). " +
  "click_slot navigue dans un menu de plugin, mais envoie un vrai clic au serveur : verifier le lore de la case avant, ou utiliser dryRun. " +
  "Pour juger un mouvement (animation, particules, transition), capture_animation remplace une image par une serie. " +
  "Apres une modification du pack : reload_resource_pack puis compare_all_references pour savoir ce qui a change visuellement. " +
  "Le mod ne voit que ce que le client recoit ; server_command (si RCON est configure) ouvre ce que seul le serveur sait, avec la sortie des commandes. " +
  "Sinon, la boucle manuelle tient en un seul appel run_steps : send_command (tp / gamemode spectator), wait_ticks, focus_entities (+ focus_scene), screenshot, focus_clear. " +
  "Regle generale : des que deux appels dependent l'un de l'autre, les passer a run_steps ; chaque appel separe coute un tour complet de conversation. " +
  "Apres une modification de pack, en un run_steps : reload_resource_pack, get_client_log (levels ERROR,WARN), screenshot.";

function log(...args: unknown[]): void {
  console.error("[mcbridge-mcp]", ...args);
}

/** Ce que le serveur MCP attend du bridge : de quoi appeler une methode. Un test y met un faux. */
export type Bridge = Pick<BridgeClient, "call">;

type Handler = (args: Record<string, unknown>) => Promise<CallToolResult>;

function registerTools(server: McpServer, bridge: Bridge): void {
  /** Un appel direct : traduction des arguments, bridge, mise en forme. run_steps l'enchaine. */
  const invoke: Invoke = async (def, args) => {
    try {
      const params = def.mapArgs ? def.mapArgs(args ?? {}) : (args ?? {});
      const result = await bridge.call(def.method, params);
      return convertResult(def, result);
    } catch (err) {
      return errorResult(err);
    }
  };
  // Tools composes cote serveur : pas de methode RPC, un handler ici.
  const localHandlers: Record<string, Handler> = {
    [RUN_STEPS]: (args) => runSteps(TOOLS, args ?? {}, invoke),
  };
  for (const def of TOOLS as ToolDef[]) {
    const config = {
      title: def.title,
      description: def.description,
      inputSchema: def.inputSchema,
      ...(def.annotations ? { annotations: def.annotations } : {}),
    };
    let handler: Handler;
    if (def.local) {
      const local = localHandlers[def.name];
      if (!local) throw new Error(`tool compose sans handler cote serveur : ${def.name}`);
      handler = local;
    } else {
      handler = (args) => invoke(def, args ?? {});
    }
    server.registerTool(def.name, config, handler);
  }
}

export function buildServer(bridge: Bridge): McpServer {
  const server = new McpServer({ name: "mcbridge", version: PKG_VERSION }, { instructions: INSTRUCTIONS });
  registerTools(server, bridge);
  return server;
}

async function runStdio(bridge: BridgeClient, cfg: ServerConfig): Promise<void> {
  const server = buildServer(bridge);
  await server.connect(new StdioServerTransport());
  log(`stdio pret (bridge ${cfg.bridgeUrl}, jeton : ${cfg.tokenSource}, ${TOOLS.length} tools)`);
}

async function runHttp(bridge: BridgeClient, cfg: ServerConfig): Promise<void> {
  const sessions = new Map<string, { server: McpServer; transport: StreamableHTTPServerTransport }>();

  async function readBody(req: http.IncomingMessage): Promise<unknown> {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    if (chunks.length === 0) return undefined;
    try {
      return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      return undefined;
    }
  }

  const httpServer = http.createServer(async (req, res) => {
    if (!req.url || !req.url.startsWith("/mcp")) {
      res.writeHead(404).end("Not found");
      return;
    }
    const sidHeader = req.headers["mcp-session-id"];
    const sid = Array.isArray(sidHeader) ? sidHeader[0] : sidHeader;
    const body = req.method === "POST" ? await readBody(req) : undefined;
    let entry = sid ? sessions.get(sid) : undefined;
    if (!entry) {
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (id: string) => {
          sessions.set(id, entry!);
        },
      });
      transport.onclose = () => {
        if (transport.sessionId) sessions.delete(transport.sessionId);
      };
      const server = buildServer(bridge);
      await server.connect(transport);
      entry = { server, transport };
    }
    await entry.transport.handleRequest(req, res, body);
  });

  httpServer.listen(cfg.httpPort, "127.0.0.1", () => {
    log(`transport HTTP streamable pret sur http://127.0.0.1:${cfg.httpPort}/mcp`);
  });
}

async function main(): Promise<void> {
  const cfg = loadConfig();
  const bridge = new BridgeClient(cfg.bridgeUrl, cfg.token, cfg.timeoutMs);
  bridge
    .info()
    .then((info) => log("bridge connecte :", JSON.stringify(info).slice(0, 300)))
    .catch((err) => log("bridge pas encore joignable :", (err as Error).message));
  if (cfg.transport === "http") await runHttp(bridge, cfg);
  else await runStdio(bridge, cfg);
}

// Ne lancer main() que si ce fichier est le point d'entree (le smoke test importe buildServer).
const isMain = process.argv[1] !== undefined && import.meta.url === new URL(`file://${process.argv[1]}`).href;
if (isMain) {
  main().catch((err) => {
    log("erreur fatale :", err);
    process.exit(1);
  });
}
