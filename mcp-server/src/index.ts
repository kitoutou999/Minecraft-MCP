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
import { BridgeClient, BridgeError, BridgeUnreachableError } from "./bridge.js";
import { TOOLS, type ToolDef } from "./tools.js";

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
  "Sinon, boucle manuelle : send_command (tp / gamemode spectator) -> wait_ticks -> focus_entities (+ focus_scene) -> screenshot -> focus_clear. " +
  "Apres une modification de pack : reload_resource_pack -> get_client_log levels ERROR,WARN -> screenshot.";

function log(...args: unknown[]): void {
  console.error("[mcbridge-mcp]", ...args);
}

function errorResult(err: unknown): CallToolResult {
  let text: string;
  if (err instanceof BridgeUnreachableError) text = `Bridge injoignable : ${err.message}`;
  else if (err instanceof BridgeError) {
    text = `Erreur du bridge [${err.code}] : ${err.message}`;
    if (err.data !== undefined) text += `\n${JSON.stringify(err.data)}`;
  } else if (err instanceof Error) text = `Erreur : ${err.message}`;
  else text = `Erreur : ${String(err)}`;
  return { isError: true, content: [{ type: "text", text }] };
}

function jsonResult(result: unknown): CallToolResult {
  if (result === undefined || result === null) return { content: [{ type: "text", text: "ok" }] };
  const text = typeof result === "string" ? result : JSON.stringify(result, null, 2);
  const out: CallToolResult = { content: [{ type: "text", text }] };
  if (typeof result === "object") out.structuredContent = result as Record<string, unknown>;
  return out;
}

interface ImagePayload {
  base64: string;
  mimeType?: string;
  format?: string;
  width?: number;
  height?: number;
  bytes?: number;
  capture?: Record<string, unknown>;
}

function imageResult(result: unknown): CallToolResult {
  const r = result as ImagePayload;
  if (!r || typeof r.base64 !== "string") return errorResult(new Error("Le bridge n'a pas renvoye d'image."));
  const mimeType = r.mimeType ?? (r.format === "jpeg" ? "image/jpeg" : "image/png");
  const { base64: _omit, ...meta } = r;
  return {
    content: [
      { type: "image", data: r.base64, mimeType },
      { type: "text", text: `Capture ${r.width ?? "?"}x${r.height ?? "?"} ${mimeType}, ${r.bytes ?? "?"} octets. ${JSON.stringify(meta.capture ?? {})}` },
    ],
  };
}

/** Resultat d'un tool studio : plusieurs vues, chacune avec son image et son angle. */
interface ShotsPayload {
  shots?: Array<ImagePayload & { angle?: { name?: string }; camera?: Record<string, unknown> }>;
  [key: string]: unknown;
}

function imagesResult(result: unknown): CallToolResult {
  const r = result as ShotsPayload;
  // Certains tools renvoient une image unique (planche d'animation) plutot qu'une serie.
  if (typeof (result as Partial<ImagePayload> | undefined)?.base64 === "string") return imageResult(result);
  const shots = Array.isArray(r?.shots) ? r.shots : [];
  if (shots.length === 0) return jsonResult(result);
  const content: CallToolResult["content"] = [];
  for (const shot of shots) {
    if (typeof shot.base64 !== "string") continue;
    const mimeType = shot.mimeType ?? (shot.format === "jpeg" ? "image/jpeg" : "image/png");
    content.push({ type: "image", data: shot.base64, mimeType });
    content.push({
      type: "text",
      text: `Vue ${shot.angle?.name ?? "?"} : ${shot.width ?? "?"}x${shot.height ?? "?"}, camera ${JSON.stringify(shot.camera ?? {})}`,
    });
  }
  // Metadonnees sans les images, pour que le modele garde le contexte du cadrage.
  const meta = { ...r, shots: shots.map(({ base64: _b, ...rest }) => rest) };
  content.push({ type: "text", text: JSON.stringify(meta, null, 2) });
  return { content, structuredContent: meta as Record<string, unknown> };
}

/** Resultat d'une comparaison de reference : des mesures, et une image des differences si besoin. */
function diffResult(result: unknown): CallToolResult {
  const r = result as { diffBase64?: string; diffMimeType?: string; identical?: boolean; [k: string]: unknown };
  const { diffBase64, ...meta } = r ?? {};
  const content: CallToolResult["content"] = [];
  if (typeof diffBase64 === "string") {
    content.push({ type: "image", data: diffBase64, mimeType: r.diffMimeType ?? "image/png" });
    content.push({ type: "text", text: "Differences en magenta sur fond grise." });
  }
  content.push({ type: "text", text: JSON.stringify(meta, null, 2) });
  return { content, structuredContent: meta as Record<string, unknown> };
}

function registerTools(server: McpServer, bridge: BridgeClient): void {
  for (const def of TOOLS as ToolDef[]) {
    const config = {
      title: def.title,
      description: def.description,
      inputSchema: def.inputSchema,
      ...(def.annotations ? { annotations: def.annotations } : {}),
    };
    const handler = async (args: Record<string, unknown>): Promise<CallToolResult> => {
      try {
        const params = def.mapArgs ? def.mapArgs(args ?? {}) : (args ?? {});
        const result = await bridge.call(def.method, params);
        if (def.kind === "image") return imageResult(result);
        if (def.kind === "images") return imagesResult(result);
        if (def.kind === "diff") return diffResult(result);
        return jsonResult(result);
      } catch (err) {
        return errorResult(err);
      }
    };
    server.registerTool(def.name, config, handler);
  }
}

export function buildServer(bridge: BridgeClient): McpServer {
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
