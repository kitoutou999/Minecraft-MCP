/**
 * Conversion des reponses du mod en resultats MCP.
 *
 * Le texte d'un resultat est ce que le modele lit, donc ce qu'il paie en tokens, et il le repaie a
 * chaque tour tant que la conversation dure. D'ou deux regles appliquees a tout ce qui sort d'ici :
 * JSON compact, sans indentation, et flottants arrondis (une position vaut 78.3, pas
 * 78.29999995231628). Une image part en bloc `image` et n'est jamais repetee dans le texte.
 */
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

import { BridgeError, BridgeUnreachableError } from "./bridge.js";
import type { ToolDef } from "./tools.js";

/**
 * Arrondit un flottant pour la lecture : trois decimales a partir de 1 (le millieme de bloc), quatre
 * chiffres significatifs en deca pour ne pas ecraser une petite valeur (vitesse, pourcentage de
 * pixels differents). Les entiers et les valeurs non finies restent tels quels.
 */
export function roundNumber(x: number): number {
  if (!Number.isFinite(x)) return x;
  if (Number.isInteger(x)) return x === 0 ? 0 : x; // -0 s'ecrit 0
  return Math.abs(x) >= 1 ? Math.round(x * 1000) / 1000 : Number(x.toPrecision(4));
}

/** Copie d'une valeur JSON avec tous ses flottants arrondis ; chaines et entiers sont intacts. */
export function roundNumbers(value: unknown): unknown {
  if (typeof value === "number") return roundNumber(value);
  if (Array.isArray(value)) return value.map(roundNumbers);
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) out[k] = roundNumbers(v);
    return out;
  }
  return value;
}

/** Serialisation compacte et arrondie : la forme sous laquelle le modele lit tout resultat. */
export function compactJson(value: unknown): string {
  return JSON.stringify(roundNumbers(value));
}

export function errorResult(err: unknown): CallToolResult {
  let text: string;
  if (err instanceof BridgeUnreachableError) text = `Bridge injoignable : ${err.message}`;
  else if (err instanceof BridgeError) {
    text = `Erreur du bridge [${err.code}] : ${err.message}`;
    if (err.data !== undefined) text += `\n${compactJson(err.data)}`;
  } else if (err instanceof Error) text = `Erreur : ${err.message}`;
  else text = `Erreur : ${String(err)}`;
  return { isError: true, content: [{ type: "text", text }] };
}

export function jsonResult(result: unknown): CallToolResult {
  if (result === undefined || result === null) return { content: [{ type: "text", text: "ok" }] };
  if (typeof result === "string") return { content: [{ type: "text", text: result }] };
  const rounded = roundNumbers(result);
  const out: CallToolResult = { content: [{ type: "text", text: JSON.stringify(rounded) }] };
  // `structuredContent` doit etre un objet : un tableau y est refuse par le protocole, et le client
  // rejette alors toute la reponse. Un handler qui renvoie une liste (reflect.getField sur une
  // collection, par exemple) reste donc lisible dans le texte, sans partie structuree.
  if (typeof rounded === "object" && !Array.isArray(rounded)) {
    out.structuredContent = rounded as Record<string, unknown>;
  }
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

function mimeOf(r: Pick<ImagePayload, "mimeType" | "format">): string {
  return r.mimeType ?? (r.format === "jpeg" ? "image/jpeg" : "image/png");
}

export function imageResult(result: unknown): CallToolResult {
  const r = result as ImagePayload;
  if (!r || typeof r.base64 !== "string") return errorResult(new Error("Le bridge n'a pas renvoye d'image."));
  const mimeType = mimeOf(r);
  return {
    content: [
      { type: "image", data: r.base64, mimeType },
      {
        type: "text",
        text: `Capture ${r.width ?? "?"}x${r.height ?? "?"} ${mimeType}, ${r.bytes ?? "?"} octets. ${compactJson(r.capture ?? {})}`,
      },
    ],
  };
}

/** Resultat d'un tool studio : plusieurs vues, chacune avec son image et son angle. */
interface ShotsPayload {
  shots?: Array<ImagePayload & { angle?: string | { name?: string } }>;
  [key: string]: unknown;
}

export function imagesResult(result: unknown): CallToolResult {
  const r = result as ShotsPayload;
  // Certains tools renvoient une image unique (planche d'animation) plutot qu'une serie.
  if (typeof (result as Partial<ImagePayload> | undefined)?.base64 === "string") return imageResult(result);
  const shots = Array.isArray(r?.shots) ? r.shots : [];
  if (shots.length === 0) return jsonResult(result);
  const content: CallToolResult["content"] = [];
  for (const shot of shots) {
    if (typeof shot.base64 !== "string") continue;
    content.push({ type: "image", data: shot.base64, mimeType: mimeOf(shot) });
    // Une ligne courte par vue ; la camera et le reste sont dans les metadonnees, une seule fois.
    const name = typeof shot.angle === "string" ? shot.angle : shot.angle?.name;
    content.push({ type: "text", text: `Vue ${name ?? "?"} : ${shot.width ?? "?"}x${shot.height ?? "?"}` });
  }
  // Metadonnees sans les images, pour que le modele garde le contexte du cadrage.
  const meta = roundNumbers({ ...r, shots: shots.map(({ base64: _b, ...rest }) => rest) }) as Record<string, unknown>;
  content.push({ type: "text", text: JSON.stringify(meta) });
  return { content, structuredContent: meta };
}

/** Resultat d'une comparaison de reference : des mesures, et une image des differences si besoin. */
export function diffResult(result: unknown): CallToolResult {
  const r = result as { diffBase64?: string; diffMimeType?: string; identical?: boolean; [k: string]: unknown };
  const { diffBase64, ...rest } = r ?? {};
  const meta = roundNumbers(rest) as Record<string, unknown>;
  const content: CallToolResult["content"] = [];
  if (typeof diffBase64 === "string") {
    content.push({ type: "image", data: diffBase64, mimeType: r.diffMimeType ?? "image/png" });
    content.push({ type: "text", text: "Differences en magenta sur fond grise." });
  }
  content.push({ type: "text", text: JSON.stringify(meta) });
  return { content, structuredContent: meta };
}

/**
 * Resultat d'une serie de comparaisons : chaque reference qui porte une image des differences la
 * donne en bloc image, jamais en texte. Un base64 dans le JSON coute des dizaines de milliers de
 * tokens : c'est le piege que ce chemin ferme.
 */
export function diffsResult(result: unknown): CallToolResult {
  const r = result as { results?: Array<{ name?: string; diffBase64?: string; diffMimeType?: string; [k: string]: unknown }>; [k: string]: unknown };
  const list = Array.isArray(r?.results) ? r.results : [];
  if (list.length === 0) return jsonResult(result);
  const content: CallToolResult["content"] = [];
  const stripped = list.map(({ diffBase64, ...rest }) => {
    if (typeof diffBase64 === "string") {
      content.push({ type: "image", data: diffBase64, mimeType: rest.diffMimeType ?? "image/png" });
      content.push({ type: "text", text: `Reference ${rest.name ?? "?"} : differences en magenta sur fond grise.` });
    }
    return rest;
  });
  const meta = roundNumbers({ ...r, results: stripped }) as Record<string, unknown>;
  content.push({ type: "text", text: JSON.stringify(meta) });
  return { content, structuredContent: meta };
}

/** Met en forme la reponse du mod selon le `kind` declare par le tool. */
export function convertResult(def: Pick<ToolDef, "kind">, result: unknown): CallToolResult {
  if (def.kind === "image") return imageResult(result);
  if (def.kind === "images") return imagesResult(result);
  if (def.kind === "diff") return diffResult(result);
  if (def.kind === "diffs") return diffsResult(result);
  return jsonResult(result);
}
