// Mise en forme des resultats : c'est le texte que le modele lit, donc ce qu'il paie en tokens a
// chaque tour. Ces tests fixent la forme compacte et l'arrondi, pour qu'une retouche ne reintroduise
// pas l'indentation ou les flottants a seize chiffres.
import { test, describe } from "node:test";
import assert from "node:assert/strict";

const { roundNumber, roundNumbers, compactJson, jsonResult, imageResult, imagesResult, diffResult, diffsResult, errorResult } =
  await import("../dist/results.js");
const { BridgeError } = await import("../dist/bridge.js");

const pixel = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

describe("arrondi des flottants", () => {
  test("une coordonnee garde trois decimales", () => {
    assert.equal(roundNumber(78.29999995231628), 78.3);
    assert.equal(roundNumber(1.399999976158142), 1.4);
    assert.equal(roundNumber(12345678.123456), 12345678.123);
    assert.equal(roundNumber(-86.89999997615814), -86.9);
  });

  test("une petite valeur garde quatre chiffres significatifs plutot que de tomber a zero", () => {
    assert.equal(roundNumber(0.16666666666666666), 0.1667);
    assert.equal(roundNumber(0.000049999999), 0.00005);
    assert.equal(roundNumber(-0.00001), -0.00001);
    assert.equal(roundNumber(0.5), 0.5);
  });

  test("les entiers, le zero negatif et les valeurs non finies restent tels quels", () => {
    assert.equal(roundNumber(654), 654);
    assert.equal(roundNumber(0), 0);
    assert.equal(roundNumber(-0.0000000001), -1e-10, "un tres petit negatif garde son signe et sa valeur");
    assert.equal(roundNumber(-0), 0);
    assert.ok(Number.isNaN(roundNumber(NaN)));
    assert.equal(roundNumber(Infinity), Infinity);
  });

  test("l'arrondi traverse objets et tableaux sans toucher aux chaines ni a l'original", () => {
    const input = { pos: { x: 78.29999995231628, y: 85.5 }, ids: [1, 2.000001], uuid: "64e0fbd2-1.999999", base64: pixel };
    const out = roundNumbers(input);
    assert.deepEqual(out, { pos: { x: 78.3, y: 85.5 }, ids: [1, 2], uuid: "64e0fbd2-1.999999", base64: pixel });
    assert.equal(input.pos.x, 78.29999995231628, "l'objet d'origine n'est pas modifie");
    assert.equal(roundNumbers(null), null);
  });
});

describe("serialisation compacte", () => {
  test("aucune indentation ni espace de structure", () => {
    const text = compactJson({ a: 1.5, b: [1, 2], c: "x y", d: { e: null } });
    assert.equal(text, '{"a":1.5,"b":[1,2],"c":"x y","d":{"e":null}}');
  });

  test("mesure : un resultat de cadrage perd pres de la moitie de son texte", () => {
    // Forme reelle d'un resultat frame_target : positions repetees, distances, remplissage.
    const part = (id) => ({ id, uuid: "de33a53e-85c2-4d7c-8529-a60765ec9644", pos: { x: 78.5, y: 86.00000023841858, z: 84.5 },
      distance: 0.8999999761581421, boundsSource: "hitbox" });
    const result = { target: part(654), bounds: { radius: 1.8167133503322392, parts: [655, 656, 657, 658].map(part) },
      shots: [{ distanceUsed: 4.312499999999999, refineFill: 0.7229166666666667 }] };
    const pretty = JSON.stringify(result, null, 2).length;
    const compact = compactJson(result).length;
    assert.ok(compact < pretty * 0.6, `compact ${compact} contre indente ${pretty}`);
  });
});

describe("resultats JSON", () => {
  test("un objet donne un texte compact et une partie structuree arrondie", () => {
    const res = jsonResult({ tick: 42, pos: { x: 1.23456789 } });
    assert.equal(res.content[0].text, '{"tick":42,"pos":{"x":1.235}}');
    assert.deepEqual(res.structuredContent, { tick: 42, pos: { x: 1.235 } });
  });

  test("un tableau reste lisible sans partie structuree, une chaine et un nombre passent tels quels", () => {
    const list = jsonResult(["stop", 1.00001]);
    assert.equal(list.structuredContent, undefined);
    assert.equal(list.content[0].text, '["stop",1]');
    assert.equal(jsonResult("brut").content[0].text, "brut");
    assert.equal(jsonResult(30000).content[0].text, "30000");
    assert.equal(jsonResult(undefined).content[0].text, "ok");
  });

  test("une erreur du bridge garde son code et ses donnees, compactes", () => {
    const res = errorResult(new BridgeError({ code: "busy", message: "occupe", data: { by: "studio.frameTarget", since: 1.5 } }));
    assert.equal(res.isError, true);
    assert.equal(res.content[0].text, 'Erreur du bridge [busy] : occupe\n{"by":"studio.frameTarget","since":1.5}');
  });
});

describe("resultats avec image", () => {
  test("une capture donne un bloc image puis ses mesures, jamais le base64 en texte", () => {
    const res = imageResult({ base64: pixel, format: "jpeg", width: 960, height: 540, bytes: 12345, capture: { yaw: 179.99999 } });
    assert.equal(res.content[0].type, "image");
    assert.equal(res.content[0].mimeType, "image/jpeg");
    assert.equal(res.content[1].text, 'Capture 960x540 image/jpeg, 12345 octets. {"yaw":180}');
    assert.equal(imageResult({}).isError, true, "sans base64, c'est une erreur lisible");
  });

  test("une serie de vues donne une image par vue et des metadonnees sans base64", () => {
    const shots = [
      { base64: pixel, format: "png", width: 1, height: 1, angle: { name: "front" }, camera: { distance: 3.00001 } },
      { base64: pixel, format: "png", width: 1, height: 1, angle: { name: "back" }, camera: { distance: 3 } },
    ];
    const res = imagesResult({ shots, eyeHeight: 1.6200000047683716 });
    assert.equal(res.content.filter((c) => c.type === "image").length, 2);
    assert.equal(res.content[1].text, "Vue front : 1x1", "une ligne courte par vue, la camera n'est ecrite qu'une fois, dans les metadonnees");
    const meta = res.content[res.content.length - 1].text;
    assert.ok(!meta.includes(pixel));
    assert.ok(!meta.includes("\n"), "metadonnees compactes");
    assert.match(meta, /"eyeHeight":1\.62[,}]/);
    assert.match(meta, /"camera":\{"distance":3\}/);
    assert.equal(imagesResult({ base64: pixel, width: 2, height: 2 }).content[0].type, "image", "une planche unique passe par le chemin image");
  });

  test("le resume de frame_target nomme l'angle par une chaine, et la ligne de vue le reprend", () => {
    const res = imagesResult({ shots: [{ base64: pixel, format: "jpeg", width: 640, height: 400, angle: "iso", fill: 0.72 }], captured: true });
    assert.equal(res.content[1].text, "Vue iso : 640x400");
    assert.equal(res.content[2].text, '{"shots":[{"format":"jpeg","width":640,"height":400,"angle":"iso","fill":0.72}],"captured":true}');
  });

  test("une serie de comparaisons donne une image par reference modifiee, jamais de base64 en texte", () => {
    const res = diffsResult({ total: 2, changed: 1, results: [
      { name: "boss_front", changed: true, percentDiffering: 3.43219, diffBase64: pixel, diffWidth: 1, diffHeight: 1 },
      { name: "chest", changed: false, percentDiffering: 0.1999 },
    ] });
    assert.equal(res.content[0].type, "image");
    assert.equal(res.content[1].text, "Reference boss_front : differences en magenta sur fond grise.");
    const meta = res.content[2].text;
    assert.ok(!meta.includes(pixel), "le base64 ne doit jamais passer en texte");
    assert.match(meta, /"boss_front","changed":true,"percentDiffering":3\.432/);
    assert.equal(diffsResult({ total: 0, results: [] }).content[0].text, '{"total":0,"results":[]}', "sans reference, un JSON simple");
  });

  test("une comparaison donne ses mesures arrondies, et l'image des differences seulement si elle existe", () => {
    const withImage = diffResult({ diffBase64: pixel, percentDiffering: 3.4321987, identical: false });
    assert.equal(withImage.content[0].type, "image");
    assert.equal(withImage.content[2].text, '{"percentDiffering":3.432,"identical":false}');
    const without = diffResult({ percentDiffering: 0.004321, identical: false });
    assert.equal(without.content.length, 1);
    assert.equal(without.content[0].text, '{"percentDiffering":0.004321,"identical":false}');
  });
});
