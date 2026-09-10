// Bout en bout, sans Minecraft : un faux bridge repond, le vrai serveur MCP tourne en stdio, et un
// client MCP verifie ce qui ressort. C'est le seul niveau qui exerce la conversion des reponses du
// mod en resultats MCP, et donc la validation du protocole par le client.
import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const here = dirname(fileURLToPath(import.meta.url));

let bridge;
let client;
/** Reponse du faux bridge, remplacee par chaque test. */
let result = { ok: true, result: {} };

before(async () => {
  bridge = http.createServer((req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(req.url?.startsWith("/info") ? { ok: true, mod: "faux" } : result));
  });
  await new Promise((resolve) => bridge.listen(0, "127.0.0.1", resolve));

  client = new Client({ name: "test", version: "0.0.0" });
  await client.connect(new StdioClientTransport({
    command: process.execPath,
    args: [join(here, "..", "dist", "index.js")],
    env: {
      ...process.env,
      MCBRIDGE_URL: `http://127.0.0.1:${bridge.address().port}`,
      MCBRIDGE_TOKEN: "test",
      MCBRIDGE_TIMEOUT_MS: "3000",
    },
    stderr: "ignore",
  }));
});

after(async () => {
  await client.close();
  bridge.close();
});

describe("catalogue expose", () => {
  test("tous les tools sont enregistres avec leur schema", async () => {
    const { tools } = await client.listTools();
    assert.ok(tools.length >= 48, `seulement ${tools.length} tools`);
    const screenshot = tools.find((t) => t.name === "screenshot");
    assert.ok(screenshot.description.length > 40);
    assert.ok(screenshot.inputSchema.properties.teleport, "le schema doit arriver au client");
  });
});

describe("conversion des reponses du mod", () => {
  test("un objet devient du texte et une partie structuree", async () => {
    result = { ok: true, result: { inWorld: true, tick: 42 } };
    const res = await client.callTool({ name: "get_status", arguments: {} });
    assert.equal(res.isError, undefined);
    assert.deepEqual(res.structuredContent, { inWorld: true, tick: 42 });
    assert.equal(res.content[0].text, '{"inWorld":true,"tick":42}', "JSON compact : pas d'indentation, c'est du texte que le modele paie");
  });

  // Le protocole n'accepte qu'un objet en partie structuree. Un tableau y faisait echouer la
  // validation cote client, et toute la reponse etait perdue : constate en jeu sur reflect_get_field
  // applique a une liste.
  test("un tableau passe sans casser la validation du protocole", async () => {
    result = { ok: true, result: ["stop", "restart", "reload"] };
    const res = await client.callTool({ name: "var_get", arguments: { name: "blocked" } });
    assert.equal(res.isError, undefined);
    assert.equal(res.structuredContent, undefined, "un tableau ne peut pas etre la partie structuree");
    assert.match(res.content[0].text, /restart/, "mais son contenu reste lisible");
  });

  test("une valeur simple reste lisible", async () => {
    result = { ok: true, result: 30000 };
    const res = await client.callTool({ name: "var_get", arguments: { name: "busyTimeoutMs" } });
    assert.equal(res.isError, undefined);
    assert.equal(res.content[0].text, "30000");
  });

  test("une image devient un bloc image accompagne de ses mesures", async () => {
    const pixel = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    result = { ok: true, result: { base64: pixel, format: "png", width: 1, height: 1, bytes: 70, capture: { tick: 7 } } };
    const res = await client.callTool({ name: "screenshot", arguments: {} });
    assert.equal(res.content[0].type, "image");
    assert.equal(res.content[0].mimeType, "image/png");
    assert.equal(res.content[0].data, pixel);
    assert.match(res.content[1].text, /1x1/);
  });

  test("plusieurs vues donnent plusieurs images, et les metadonnees sans le base64", async () => {
    const pixel = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    result = { ok: true, result: { shots: [
      { base64: pixel, format: "png", width: 1, height: 1, angle: { name: "front" }, camera: { distance: 3 } },
      { base64: pixel, format: "png", width: 1, height: 1, angle: { name: "back" }, camera: { distance: 3 } },
    ] } };
    const res = await client.callTool({ name: "frame_target", arguments: { type: "item_display" } });
    const images = res.content.filter((c) => c.type === "image");
    assert.equal(images.length, 2);
    const meta = res.content[res.content.length - 1].text;
    assert.ok(!meta.includes(pixel), "les images ne doivent pas etre repetees dans le texte");
    assert.match(meta, /front/);
  });
});

describe("erreurs", () => {
  test("une erreur du mod devient un resultat d'erreur lisible, pas une exception", async () => {
    result = { ok: false, error: { code: "busy", message: "Le client est deja occupe par 'studio.frameTarget'" } };
    const res = await client.callTool({ name: "screenshot", arguments: {} });
    assert.equal(res.isError, true);
    assert.match(res.content[0].text, /busy/);
    assert.match(res.content[0].text, /frameTarget/);
  });

  test("un argument invalide est refuse avant d'atteindre le mod", async () => {
    result = { ok: true, result: {} };
    // Le schema du catalogue est applique cote serveur : l'appel revient en erreur au lieu de
    // partir vers le mod, et le modele lit pourquoi.
    for (const args of [{ ticks: 0 }, { ticks: "beaucoup" }, { ticks: 9999 }]) {
      const res = await client.callTool({ name: "wait_ticks", arguments: args });
      assert.equal(res.isError, true, `${JSON.stringify(args)} aurait du etre refuse`);
      assert.match(res.content[0].text, /validation|Invalid/i);
    }
    const ok = await client.callTool({ name: "wait_ticks", arguments: { ticks: 5 } });
    assert.equal(ok.isError, undefined, "une valeur correcte doit passer");
  });
});
