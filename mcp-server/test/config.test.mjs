// Configuration du serveur MCP : d'ou viennent l'adresse du bridge et le jeton.
// Ces regles ont un cout de diagnostic eleve quand elles se trompent (jeton d'un autre profil,
// option de plugin vide prise pour une valeur), et se verifient tres bien hors du jeu.
import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const { loadConfig } = await import("../dist/config.js");

/** Un fichier de configuration de mod, comme celui que le jeu ecrit au premier lancement. */
function configFile(content) {
  const dir = mkdtempSync(join(tmpdir(), "mcbridge-test-"));
  const path = join(dir, "mcbridge.json");
  writeFileSync(path, typeof content === "string" ? content : JSON.stringify(content));
  return path;
}

describe("adresse du bridge", () => {
  test("valeur par defaut sur la boucle locale", () => {
    const cfg = loadConfig({ MCBRIDGE_TOKEN: "t" });
    assert.equal(cfg.bridgeUrl, "http://127.0.0.1:25580");
    assert.equal(cfg.transport, "stdio");
    assert.equal(cfg.timeoutMs, 120000);
  });

  test("le slash final est retire pour ne pas doubler celui des routes", () => {
    const cfg = loadConfig({ MCBRIDGE_URL: "http://192.168.1.20:25580///", MCBRIDGE_TOKEN: "t" });
    assert.equal(cfg.bridgeUrl, "http://192.168.1.20:25580");
  });

  test("une option de plugin non renseignee arrive vide et ne doit pas ecraser le defaut", () => {
    const cfg = loadConfig({ MCBRIDGE_URL: "", MCBRIDGE_TOKEN: "t" });
    assert.equal(cfg.bridgeUrl, "http://127.0.0.1:25580");
  });
});

describe("transport", () => {
  test("http est accepte, avec son port par defaut", () => {
    const cfg = loadConfig({ MCBRIDGE_TRANSPORT: "HTTP", MCBRIDGE_TOKEN: "t" });
    assert.equal(cfg.transport, "http");
    assert.equal(cfg.httpPort, 25581);
  });

  test("un transport inconnu echoue au demarrage plutot qu'a l'usage", () => {
    assert.throws(() => loadConfig({ MCBRIDGE_TRANSPORT: "websocket" }), /stdio.*http/i);
  });
});

describe("jeton", () => {
  test("la variable d'environnement est prioritaire", () => {
    const path = configFile({ token: "jeton-du-fichier" });
    const cfg = loadConfig({ MCBRIDGE_TOKEN: "jeton-direct", MCBRIDGE_CONFIG: path });
    assert.equal(cfg.token, "jeton-direct");
    assert.equal(cfg.tokenSource, "MCBRIDGE_TOKEN");
  });

  test("a defaut, le jeton est lu dans la configuration du mod", () => {
    const path = configFile({ token: "jeton-du-fichier", port: 25580 });
    const cfg = loadConfig({ MCBRIDGE_CONFIG: path });
    assert.equal(cfg.token, "jeton-du-fichier");
    assert.equal(cfg.tokenSource, path, "la source est rappelee pour diagnostiquer un mauvais profil");
  });

  test("un fichier illisible ou sans jeton ne fait pas echouer le demarrage", () => {
    for (const content of ["{ pas du json", "{}", '{"token": ""}']) {
      const cfg = loadConfig({ MCBRIDGE_CONFIG: configFile(content), MCBRIDGE_URL: "http://127.0.0.1:1" });
      assert.equal(typeof cfg.bridgeUrl, "string", "le serveur demarre quand meme");
    }
  });

  test("un chemin inexistant est ignore sans lever", () => {
    const cfg = loadConfig({ MCBRIDGE_CONFIG: "/n/existe/pas/mcbridge.json", MCBRIDGE_TOKEN: "t" });
    assert.equal(cfg.token, "t");
  });
});

describe("delais", () => {
  test("le delai par appel est reglable", () => {
    assert.equal(loadConfig({ MCBRIDGE_TIMEOUT_MS: "5000", MCBRIDGE_TOKEN: "t" }).timeoutMs, 5000);
  });

  test("une valeur non numerique retombe sur le defaut au lieu de donner NaN", () => {
    assert.equal(loadConfig({ MCBRIDGE_TIMEOUT_MS: "beaucoup", MCBRIDGE_TOKEN: "t" }).timeoutMs, 120000);
  });
});
