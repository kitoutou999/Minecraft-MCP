// Catalogue des tools : ce que voit le modele. Une incoherence ici ne casse rien a la compilation
// mais fait choisir le mauvais outil, ou envoie des parametres que le mod ne comprend pas.
import { test, describe } from "node:test";
import assert from "node:assert/strict";

const { TOOLS } = await import("../dist/tools.js");

describe("forme du catalogue", () => {
  test("chaque tool a un nom unique en snake_case", () => {
    const seen = new Set();
    for (const t of TOOLS) {
      assert.match(t.name, /^[a-z][a-z0-9_]*$/, `nom invalide : ${t.name}`);
      assert.ok(!seen.has(t.name), `nom en double : ${t.name}`);
      seen.add(t.name);
    }
  });

  test("chaque methode RPC s'ecrit espace.action", () => {
    for (const t of TOOLS) {
      assert.match(t.method, /^[a-z][a-zA-Z]*\.[a-zA-Z]+$/, `${t.name} vise "${t.method}"`);
    }
  });

  test("chaque tool porte un titre et une description utile au choix", () => {
    for (const t of TOOLS) {
      assert.ok(t.title?.length > 0, `${t.name} sans titre`);
      assert.ok(t.description?.length >= 40, `${t.name} : description trop courte pour choisir`);
    }
  });

  test("les tools qui renvoient des images le declarent", () => {
    const kinds = new Map(TOOLS.map((t) => [t.name, t.kind]));
    assert.equal(kinds.get("screenshot"), "image");
    assert.equal(kinds.get("screenshot_gui"), "image");
    assert.equal(kinds.get("frame_target"), "images");
    assert.equal(kinds.get("capture_animation"), "images");
    assert.equal(kinds.get("compare_reference"), "diff");
    assert.equal(kinds.get("compare_all_references"), "diffs", "avec includeDiffImages, un base64 en texte couterait des dizaines de milliers de tokens");
    for (const t of TOOLS) {
      if (t.kind) assert.ok(["json", "image", "images", "diff", "diffs"].includes(t.kind), `${t.name} : kind ${t.kind}`);
    }
  });
});

describe("annotations", () => {
  test("une action qui prend le controle du client n'est pas annoncee sans effet", () => {
    // Ces cinq-la deplacent la camera ou masquent le decor le temps de l'appel.
    for (const name of ["screenshot", "capture_animation", "screenshot_gui", "compare_reference", "compare_all_references"]) {
      const t = TOOLS.find((x) => x.name === name);
      assert.ok(t, `${name} absent du catalogue`);
      assert.notEqual(t.annotations?.readOnlyHint, true, `${name} ne doit pas se dire readOnly`);
    }
  });

  test("les lectures pures restent annoncees readOnly", () => {
    for (const name of ["get_status", "get_player", "get_gui", "get_item_lore", "studio_bounds", "list_entities"]) {
      const t = TOOLS.find((x) => x.name === name);
      assert.equal(t.annotations?.readOnlyHint, true, `${name} devrait etre readOnly`);
    }
  });

  test("le clic dans un menu est signale comme agissant sur le serveur", () => {
    const click = TOOLS.find((t) => t.name === "click_slot");
    assert.equal(click.annotations?.destructiveHint, true);
    assert.match(click.description, /dryRun/, "la description doit rappeler l'echappatoire sans effet");
  });
});

describe("tools composes", () => {
  test("run_steps est compose cote serveur et ne se dit pas readOnly", () => {
    const t = TOOLS.find((x) => x.name === "run_steps");
    assert.ok(t, "run_steps absent du catalogue");
    assert.equal(t.local, true);
    assert.notEqual(t.annotations?.readOnlyHint, true, "une sequence porte les effets de ses etapes");
    assert.match(t.description, /focus_clear/, "la description doit montrer la boucle qu'il remplace");
    assert.match(t.description, /stopOnError/, "et rappeler comment garantir une etape finale");
  });

  test("run_steps est le seul tool sans methode RPC", () => {
    assert.deepEqual(TOOLS.filter((t) => t.local).map((t) => t.name), ["run_steps"]);
  });
});

describe("traduction des arguments", () => {
  test("send_command ajoute le slash et ne le double jamais", () => {
    const t = TOOLS.find((x) => x.name === "send_command");
    assert.deepEqual(t.mapArgs({ command: "time set noon" }), { message: "/time set noon", vanilla: false });
    assert.deepEqual(t.mapArgs({ command: "/time set noon" }), { message: "/time set noon", vanilla: false });
    assert.deepEqual(t.mapArgs({ command: "///tp" }).message, "/tp");
  });

  test("send_command sait viser la commande vanilla", () => {
    const t = TOOLS.find((x) => x.name === "send_command");
    assert.equal(t.mapArgs({ command: "tp @s 0 64 0", vanilla: true }).vanilla, true);
    assert.equal(t.mapArgs({ command: "tp" }).vanilla, false, "l'option est fausse par defaut, pas indefinie");
  });

  test("send_chat et send_command passent par la meme methode mais pas par le meme chemin", () => {
    const chat = TOOLS.find((x) => x.name === "send_chat");
    const command = TOOLS.find((x) => x.name === "send_command");
    assert.equal(chat.method, command.method);
    assert.ok(!chat.mapArgs, "un message de chat part tel quel");
    assert.ok(command.mapArgs, "une commande recoit son slash");
  });
});

describe("schemas", () => {
  test("chaque parametre porte une description", () => {
    const undescribed = [];
    for (const t of TOOLS) {
      for (const [param, schema] of Object.entries(t.inputSchema ?? {})) {
        let s = schema;
        let described = false;
        for (;;) {
          if (s?._def?.description) described = true;
          const kind = s?._def?.typeName;
          if (kind === "ZodOptional" || kind === "ZodDefault" || kind === "ZodNullable") {
            s = s._def.innerType;
            continue;
          }
          break;
        }
        if (!described) undescribed.push(`${t.name}.${param}`);
      }
    }
    assert.deepEqual(undescribed, [], "parametres sans .describe()");
  });

  test("les schemas valident et rejettent ce qu'il faut", () => {
    const look = TOOLS.find((t) => t.name === "look");
    assert.equal(look.inputSchema.pitch.safeParse(45).success, true);
    assert.equal(look.inputSchema.pitch.safeParse(120).success, false, "un pitch au-dela de 90 n'a pas de sens");
    const wait = TOOLS.find((t) => t.name === "wait_ticks");
    assert.equal(wait.inputSchema.ticks.safeParse(0).success, false, "attendre zero tick est une erreur d'appel");
    assert.equal(wait.inputSchema.ticks.parse(undefined), 20, "valeur par defaut appliquee");
  });
});
