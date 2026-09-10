// Client HTTP du bridge : c'est lui qui transforme une panne en message lisible par le modele.
// Un serveur factice suffit a verifier chaque cas sans lancer Minecraft.
import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";

const { BridgeClient, BridgeError, BridgeUnreachableError } = await import("../dist/bridge.js");

let server;
let baseUrl;
let lastRequest = null;
/** Reponse que le faux bridge renverra au prochain appel. */
let respond = () => ({ status: 200, body: { ok: true, result: { pong: true } } });

before(async () => {
  server = http.createServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      lastRequest = { url: req.url, method: req.method, headers: req.headers, body: raw ? JSON.parse(raw) : undefined };
      const { status, body, delayMs } = respond(lastRequest);
      const send = () => {
        res.writeHead(status, { "content-type": "application/json" });
        res.end(typeof body === "string" ? body : JSON.stringify(body));
      };
      if (delayMs) setTimeout(send, delayMs);
      else send();
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

describe("appel reussi", () => {
  test("la methode et les parametres partent dans le corps, le jeton dans l'entete", async () => {
    respond = () => ({ status: 200, body: { ok: true, result: { pong: true } } });
    const client = new BridgeClient(baseUrl, "secret", 2000);
    const result = await client.call("info.status", { verbose: true });

    assert.deepEqual(result, { pong: true });
    assert.equal(lastRequest.url, "/rpc");
    assert.equal(lastRequest.method, "POST");
    assert.equal(lastRequest.headers.authorization, "Bearer secret");
    assert.deepEqual(lastRequest.body, { method: "info.status", params: { verbose: true } });
  });

  test("sans jeton, aucun entete d'autorisation n'est envoye", async () => {
    respond = () => ({ status: 200, body: { ok: true, result: {} } });
    await new BridgeClient(baseUrl, undefined, 2000).call("info.status");
    assert.equal(lastRequest.headers.authorization, undefined);
  });
});

describe("erreurs du bridge", () => {
  test("une enveloppe d'erreur devient une BridgeError qui garde le code", async () => {
    respond = () => ({
      status: 200,
      body: { ok: false, error: { code: "no_player", message: "Le joueur n'est pas dans un monde", data: { tick: 12 } } },
    });
    const client = new BridgeClient(baseUrl, "secret", 2000);
    await assert.rejects(() => client.call("vision.screenshot"), (err) => {
      assert.ok(err instanceof BridgeError);
      assert.equal(err.code, "no_player");
      assert.match(err.message, /pas dans un monde/);
      assert.deepEqual(err.data, { tick: 12 });
      return true;
    });
  });

  test("le refus d'une action occupee remonte tel quel, avec son code busy", async () => {
    respond = () => ({
      status: 200,
      body: { ok: false, error: { code: "busy", message: "Le client est deja occupe par 'studio.frameTarget'" } },
    });
    await assert.rejects(() => new BridgeClient(baseUrl, "s", 2000).call("vision.screenshot"), (err) => {
      assert.equal(err.code, "busy");
      return true;
    });
  });

  test("un jeton refuse explique ou le trouver plutot que de dire 401", async () => {
    respond = () => ({ status: 401, body: { ok: false, error: { code: "unauthorized", message: "non" } } });
    await assert.rejects(() => new BridgeClient(baseUrl, "faux", 2000).call("info.status"), (err) => {
      assert.equal(err.code, "unauthorized");
      assert.match(err.message, /MCBRIDGE_TOKEN|MCBRIDGE_CONFIG/);
      return true;
    });
  });

  test("une reponse qui n'est pas du JSON est signalee, pas propagee brute", async () => {
    respond = () => ({ status: 502, body: "<html>proxy</html>" });
    await assert.rejects(() => new BridgeClient(baseUrl, "s", 2000).call("info.status"), (err) => {
      assert.equal(err.code, "bad_response");
      assert.match(err.message, /502/);
      return true;
    });
  });
});

describe("bridge injoignable", () => {
  test("Minecraft ferme donne un message qui dit quoi verifier", async () => {
    const client = new BridgeClient("http://127.0.0.1:1", "s", 1000);
    await assert.rejects(() => client.call("info.status"), (err) => {
      assert.ok(err instanceof BridgeUnreachableError);
      assert.match(err.message, /injoignable/);
      assert.match(err.message, /MCBRIDGE_URL/);
      return true;
    });
  });

  test("un appel trop long est abandonne et nomme le delai", async () => {
    respond = () => ({ status: 200, body: { ok: true, result: {} }, delayMs: 500 });
    await assert.rejects(() => new BridgeClient(baseUrl, "s", 80).call("vision.screenshot"), (err) => {
      assert.ok(err instanceof BridgeUnreachableError);
      assert.match(err.message, /delai depasse \(80 ms\)/);
      return true;
    });
  });
});
