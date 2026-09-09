/**
 * Client HTTP minimal du bridge embarque dans le mod.
 *
 * Contrat : POST /rpc avec { method, params } renvoie { ok:true, result } ou
 * { ok:false, error:{ code, message, data? } }. GET /info renvoie l'etat. Voir docs/PROTOCOL.md.
 */

export interface BridgeErrorBody {
  code: string;
  message: string;
  data?: unknown;
}

/** Le bridge a repondu mais l'appel a echoue (parametre invalide, joueur absent, refus...). */
export class BridgeError extends Error {
  readonly code: string;
  readonly data?: unknown;
  constructor(body: BridgeErrorBody) {
    super(body.message);
    this.name = "BridgeError";
    this.code = body.code;
    this.data = body.data;
  }
}

/** Le bridge est injoignable (Minecraft ferme, mod absent, mauvais port). */
export class BridgeUnreachableError extends Error {
  override readonly cause?: unknown;
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "BridgeUnreachableError";
    this.cause = cause;
  }
}

interface RpcEnvelope<T> {
  ok: boolean;
  result?: T;
  error?: BridgeErrorBody;
}

export class BridgeClient {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string | undefined,
    private readonly timeoutMs: number,
  ) {}

  private headers(): Record<string, string> {
    const h: Record<string, string> = { "content-type": "application/json" };
    if (this.token) h.authorization = `Bearer ${this.token}`;
    return h;
  }

  private async fetchWithTimeout(path: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await fetch(this.baseUrl + path, { ...init, signal: controller.signal });
    } catch (err) {
      const reason =
        err instanceof Error && err.name === "AbortError"
          ? `delai depasse (${this.timeoutMs} ms)`
          : ((err as Error)?.message ?? String(err));
      throw new BridgeUnreachableError(
        `Bridge mcbridge injoignable sur ${this.baseUrl} (${reason}). ` +
          `Minecraft est-il lance avec le mod, et MCBRIDGE_URL est-il correct ?`,
        err,
      );
    } finally {
      clearTimeout(timer);
    }
  }

  /** Appelle une methode RPC du bridge. */
  async call<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const res = await this.fetchWithTimeout("/rpc", {
      method: "POST",
      headers: this.headers(),
      body: JSON.stringify({ method, params }),
    });
    if (res.status === 401 || res.status === 403) {
      throw new BridgeError({
        code: "unauthorized",
        message:
          "Le bridge a refuse le jeton. Definir MCBRIDGE_TOKEN ou MCBRIDGE_CONFIG " +
          "(chemin vers config/mcbridge.json du client Minecraft).",
      });
    }
    let body: RpcEnvelope<T>;
    try {
      body = (await res.json()) as RpcEnvelope<T>;
    } catch {
      throw new BridgeError({ code: "bad_response", message: `Reponse non JSON du bridge (HTTP ${res.status}).` });
    }
    if (!body.ok) throw new BridgeError(body.error ?? { code: "unknown", message: "Erreur inconnue du bridge" });
    return body.result as T;
  }

  /** Sonde de vie : renvoie l'etat du bridge ou leve si injoignable. */
  async info<T = unknown>(): Promise<T> {
    const res = await this.fetchWithTimeout("/info", { method: "GET", headers: this.headers() });
    return (await res.json()) as T;
  }
}
