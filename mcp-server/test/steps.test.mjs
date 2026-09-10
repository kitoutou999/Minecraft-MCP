// run_steps : plusieurs tools en un appel. Le vrai serveur MCP tourne en memoire sur un faux bridge
// qui note chaque methode recue : on verifie l'ordre, les traductions, l'arret sur erreur et le
// refus d'une sequence invalide avant toute execution.
import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";

const { buildServer } = await import("../dist/index.js");
const { BridgeError } = await import("../dist/bridge.js");
const { prepareSteps } = await import("../dist/steps.js");
const { TOOLS } = await import("../dist/tools.js");

const pixel = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

/** Faux bridge : une reponse (valeur, fonction ou erreur a lever) par methode, et le journal des appels. */
function fakeBridge(responses = {}) {
  const calls = [];
  return {
    calls,
    async call(method, params) {
      calls.push({ method, params });
      const r = responses[method];
      if (r instanceof Error) throw r;
      return typeof r === "function" ? r(params) : (r ?? {});
    },
  };
}

async function runSteps(bridge, args) {
  const server = buildServer(bridge);
  const [a, b] = InMemoryTransport.createLinkedPair();
  await server.connect(a);
  const client = new Client({ name: "test", version: "0.0.0" });
  await client.connect(b);
  try {
    return await client.callTool({ name: "run_steps", arguments: args });
  } finally {
    await client.close();
    await server.close();
  }
}

describe("execution", () => {
  test("les etapes partent dans l'ordre, avec les memes traductions et defauts qu'un appel direct", async () => {
    const bridge = fakeBridge({ "player.getState": { pos: { x: 1.00001, y: 64, z: 2 } } });
    const res = await runSteps(bridge, {
      steps: [
        { tool: "send_command", args: { command: "tp @s 1 64 2", vanilla: true } },
        { tool: "wait_ticks" },
        { tool: "get_player" },
      ],
    });
    assert.equal(res.isError, undefined);
    assert.deepEqual(bridge.calls, [
      { method: "chat.send", params: { message: "/tp @s 1 64 2", vanilla: true } },
      { method: "game.waitTicks", params: { ticks: 20 } },
      { method: "player.getState", params: {} },
    ]);
    assert.equal(res.content.length, 3);
    assert.match(res.content[0].text, /^Etape 1\/3 send_command : /);
    assert.equal(res.content[2].text, 'Etape 3/3 get_player : {"pos":{"x":1,"y":64,"z":2}}');
    assert.deepEqual(res.structuredContent, {
      total: 3, executed: 3, failed: 0,
      steps: [{ tool: "send_command", ok: true }, { tool: "wait_ticks", ok: true }, { tool: "get_player", ok: true }],
    });
  });

  test("une image d'etape reste un bloc image, a sa place dans la sequence", async () => {
    const bridge = fakeBridge({ "vision.screenshot": { base64: pixel, format: "png", width: 1, height: 1, bytes: 70, capture: { tick: 7 } } });
    const res = await runSteps(bridge, { steps: [{ tool: "wait_ticks", args: { ticks: 2 } }, { tool: "screenshot" }] });
    assert.equal(res.isError, undefined);
    assert.equal(res.content[0].text, "Etape 1/2 wait_ticks : {}");
    assert.equal(res.content[1].text, "Etape 2/2 screenshot :");
    assert.equal(res.content[2].type, "image");
    assert.equal(res.content[2].data, pixel);
    assert.match(res.content[3].text, /^Capture 1x1/);
  });
});

describe("erreurs en cours de route", () => {
  const busy = new BridgeError({ code: "busy", message: "Le client est deja occupe par 'studio.frameTarget'" });
  const steps = [{ tool: "focus_entities", args: { ids: [1] } }, { tool: "screenshot" }, { tool: "focus_clear" }];

  test("a la premiere erreur, l'execution s'arrete et les etapes restantes sont nommees", async () => {
    const bridge = fakeBridge({ "focus.set": busy });
    const res = await runSteps(bridge, { steps });
    assert.equal(res.isError, true);
    assert.deepEqual(bridge.calls.map((c) => c.method), ["focus.set"], "rien ne part apres l'echec");
    assert.match(res.content[0].text, /^Etape 1\/3 focus_entities a echoue : Erreur du bridge \[busy\]/);
    assert.match(res.content[0].text, /Etapes non executees : 2 screenshot, 3 focus_clear\./);
    assert.equal(res.structuredContent.executed, 1);
    assert.equal(res.structuredContent.failed, 1);
    assert.deepEqual(res.structuredContent.skipped, ["2 screenshot", "3 focus_clear"]);
  });

  test("stopOnError=false execute tout, et signale quand meme l'echec", async () => {
    const bridge = fakeBridge({
      "focus.set": busy,
      "vision.screenshot": { base64: pixel, format: "png", width: 1, height: 1 },
    });
    const res = await runSteps(bridge, { steps, stopOnError: false });
    assert.equal(res.isError, true);
    assert.deepEqual(bridge.calls.map((c) => c.method), ["focus.set", "vision.screenshot", "focus.clear"]);
    assert.equal(res.structuredContent.executed, 3);
    assert.equal(res.structuredContent.failed, 1);
    assert.equal(res.structuredContent.skipped, undefined);
    assert.match(res.content[res.content.length - 1].text, /^Etape 3\/3 focus_clear : /);
  });
});

describe("refus avant execution", () => {
  test("une etape invalide bloque toute la sequence, y compris les etapes valides qui la precedent", async () => {
    const bridge = fakeBridge();
    const res = await runSteps(bridge, { steps: [{ tool: "wait_ticks" }, { tool: "wait_ticks", args: { ticks: 0 } }] });
    assert.equal(res.isError, true);
    assert.equal(bridge.calls.length, 0, "rien ne doit partir vers le mod");
    assert.match(res.content[0].text, /^Aucune etape executee : etape 2 \(wait_ticks\) : ticks : /);
  });

  test("un tool inconnu et un run_steps imbrique sont refuses, avec toutes les fautes d'un coup", async () => {
    const bridge = fakeBridge();
    const res = await runSteps(bridge, {
      steps: [{ tool: "get_player" }, { tool: "teleport_player" }, { tool: "run_steps", args: { steps: [] } }],
    });
    assert.equal(res.isError, true);
    assert.equal(bridge.calls.length, 0);
    assert.match(res.content[0].text, /etape 2 \(teleport_player\) : tool inconnu/);
    assert.match(res.content[0].text, /etape 3 \(run_steps\) : run_steps ne peut pas s'imbriquer/);
  });

  test("la liste d'etapes est bornee par le schema du tool", async () => {
    const bridge = fakeBridge();
    const tooMany = await runSteps(bridge, { steps: Array.from({ length: 21 }, () => ({ tool: "get_player" })) });
    assert.equal(tooMany.isError, true);
    const none = await runSteps(bridge, { steps: [] });
    assert.equal(none.isError, true);
    assert.equal(bridge.calls.length, 0);
  });

  test("prepareSteps applique les valeurs par defaut du schema, sans la traduction mapArgs", () => {
    const prepared = prepareSteps(TOOLS, [{ tool: "send_command", args: { command: "time set noon" } }]);
    assert.ok(Array.isArray(prepared));
    assert.deepEqual(prepared[0].args, { command: "time set noon", vanilla: false });
    assert.equal(prepared[0].def.method, "chat.send");
  });
});
