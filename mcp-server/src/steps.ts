/**
 * Tool `run_steps` : plusieurs appels du catalogue en un seul aller-retour.
 *
 * Chaque appel MCP est un tour de conversation, et chaque tour relit tout le contexte accumule.
 * La boucle usuelle (commande, attente, focus, capture, focus_clear) coute donc cinq relectures
 * pour une seule image. Ici le serveur MCP enchaine lui-meme les etapes et renvoie tous les
 * resultats d'un coup, sous la meme forme qu'un appel direct. Les etapes sont toutes validees
 * avant que la premiere ne parte : une faute de frappe dans la quatrieme ne doit pas laisser le
 * client a mi-chemin apres les trois premieres.
 */
import { z } from "zod";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

import type { ToolDef } from "./tools.js";

export const RUN_STEPS = "run_steps";

export interface StepInput {
  tool: string;
  args?: Record<string, unknown>;
}

export interface PreparedStep {
  def: ToolDef;
  /** Arguments valides par le schema du tool, valeurs par defaut comprises, avant `mapArgs`. */
  args: Record<string, unknown>;
}

/** Execute un tool du catalogue comme le ferait un appel direct : traduction, bridge, mise en forme. */
export type Invoke = (def: ToolDef, args: Record<string, unknown>) => Promise<CallToolResult>;

/** Resout et valide chaque etape avec le schema de son tool, sans rien executer. */
export function prepareSteps(tools: readonly ToolDef[], steps: StepInput[]): PreparedStep[] | { errors: string[] } {
  const byName = new Map(tools.map((t) => [t.name, t]));
  const errors: string[] = [];
  const prepared: PreparedStep[] = [];
  steps.forEach((step, i) => {
    const label = `etape ${i + 1} (${step.tool})`;
    const def = byName.get(step.tool);
    if (!def) {
      errors.push(`${label} : tool inconnu`);
      return;
    }
    if (def.local) {
      errors.push(`${label} : ${RUN_STEPS} ne peut pas s'imbriquer dans ${RUN_STEPS}`);
      return;
    }
    const parsed = z.object(def.inputSchema).safeParse(step.args ?? {});
    if (!parsed.success) {
      const issues = parsed.error.issues
        .map((it) => (it.path.length > 0 ? `${it.path.join(".")} : ` : "") + it.message)
        .join(", ");
      errors.push(`${label} : ${issues}`);
      return;
    }
    prepared.push({ def, args: parsed.data as Record<string, unknown> });
  });
  return errors.length > 0 ? { errors } : prepared;
}

function firstText(res: CallToolResult): string | undefined {
  for (const block of res.content) if (block.type === "text") return block.text;
  return undefined;
}

/** Prefixe le resultat d'une etape par son numero, en fusionnant avec le premier bloc texte. */
function labelled(label: string, blocks: CallToolResult["content"]): CallToolResult["content"] {
  if (blocks.length === 0) return [{ type: "text", text: `${label} : ok` }];
  const [first, ...rest] = blocks;
  if (first.type === "text") return [{ type: "text", text: `${label} : ${first.text}` }, ...rest];
  return [{ type: "text", text: `${label} :` }, ...blocks];
}

/** Handler du tool `run_steps` ; `args` a deja passe le schema du tool. */
export async function runSteps(tools: readonly ToolDef[], args: Record<string, unknown>, invoke: Invoke): Promise<CallToolResult> {
  const steps = (Array.isArray(args.steps) ? args.steps : []) as StepInput[];
  const stopOnError = args.stopOnError !== false;
  const prep = prepareSteps(tools, steps);
  if (!Array.isArray(prep)) {
    return { isError: true, content: [{ type: "text", text: `Aucune etape executee : ${prep.errors.join(" ; ")}.` }] };
  }

  const total = prep.length;
  const content: CallToolResult["content"] = [];
  const summary: Array<{ tool: string; ok: boolean; error?: string }> = [];
  const skipped: string[] = [];
  let failed = 0;
  for (let i = 0; i < total; i++) {
    const step = prep[i];
    const label = `Etape ${i + 1}/${total} ${step.def.name}`;
    const res = await invoke(step.def, step.args);
    if (!res.isError) {
      summary.push({ tool: step.def.name, ok: true });
      content.push(...labelled(label, res.content));
      continue;
    }
    failed++;
    const message = firstText(res) ?? "erreur sans message";
    summary.push({ tool: step.def.name, ok: false, error: message });
    let text = `${label} a echoue : ${message}`;
    if (stopOnError && i < total - 1) {
      // Nommer ce qui n'a pas tourne : un focus_clear final manquant se rattrape en un appel.
      skipped.push(...prep.slice(i + 1).map((s, j) => `${i + j + 2} ${s.def.name}`));
      text += `\nEtapes non executees : ${skipped.join(", ")}.`;
      content.push({ type: "text", text });
      break;
    }
    content.push({ type: "text", text });
  }

  const structured: Record<string, unknown> = { total, executed: summary.length, failed, steps: summary };
  if (skipped.length > 0) structured.skipped = skipped;
  return { content, structuredContent: structured, ...(failed > 0 ? { isError: true } : {}) };
}
