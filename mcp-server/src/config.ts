/**
 * Configuration du serveur MCP, entierement par variables d'environnement pour etre lancee
 * depuis n'importe quel client MCP (Claude Code, Claude Desktop...).
 *
 * Le jeton est cherche dans cet ordre :
 *   1. MCBRIDGE_TOKEN
 *   2. le fichier JSON pointe par MCBRIDGE_CONFIG (champ "token")
 *   3. decouverte automatique : ~/.minecraft/config/mcbridge.json, puis les profils Modrinth App
 *      (Linux, Windows, macOS) et les instances Prism ; le fichier modifie le plus recemment gagne.
 * Ainsi, aucune copie manuelle du jeton n'est necessaire quand le client tourne sur cette machine.
 * Si le client tourne sur une autre machine, definir MCBRIDGE_URL et MCBRIDGE_TOKEN.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export interface ServerConfig {
  /** URL du bridge HTTP embarque dans le mod. */
  bridgeUrl: string;
  /** Jeton bearer attendu par le mod (config/mcbridge.json). */
  token: string | undefined;
  /** D'ou vient le jeton (pour les logs de diagnostic). */
  tokenSource: string;
  /** Delai max par appel au bridge. */
  timeoutMs: number;
  /** Transport MCP : stdio (defaut) ou http (streamable). */
  transport: "stdio" | "http";
  /** Port du transport http. */
  httpPort: number;
}

function int(value: string | undefined, fallback: number): number {
  const n = value === undefined ? NaN : Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

/** Dossiers de profils des launchers connus, chacun contenant <profil>/config/mcbridge.json. */
function launcherProfileDirs(): string[] {
  const home = homedir();
  const appData = process.env.APPDATA ?? join(home, "AppData", "Roaming");
  return [
    join(home, ".local", "share", "ModrinthApp", "profiles"),
    join(home, "snap", "modrinth", "common", ".local", "share", "ModrinthApp", "profiles"),
    join(home, ".config", "com.modrinth.theseus", "profiles"),
    join(home, ".var", "app", "com.modrinth.ModrinthApp", "data", "com.modrinth.theseus", "profiles"),
    join(appData, "ModrinthApp", "profiles"),
    join(appData, "com.modrinth.theseus", "profiles"),
    join(home, "Library", "Application Support", "ModrinthApp", "profiles"),
    join(home, ".local", "share", "PrismLauncher", "instances"),
    join(appData, "PrismLauncher", "instances"),
  ];
}

/** Candidats de fichiers de config du mod, du plus recemment modifie au plus ancien. */
export function discoverConfigFiles(): string[] {
  const candidates: string[] = [join(homedir(), ".minecraft", "config", "mcbridge.json")];
  for (const dir of launcherProfileDirs()) {
    let entries: string[] = [];
    try {
      entries = readdirSync(dir);
    } catch {
      continue;
    }
    for (const name of entries) {
      // Modrinth : <profil>/config ; Prism : <instance>/minecraft/config ou <instance>/.minecraft/config
      for (const sub of ["config", join("minecraft", "config"), join(".minecraft", "config")]) {
        candidates.push(join(dir, name, sub, "mcbridge.json"));
      }
    }
  }
  return candidates
    .map((path) => {
      try {
        return { path, mtime: statSync(path).mtimeMs };
      } catch {
        return undefined;
      }
    })
    .filter((c): c is { path: string; mtime: number } => c !== undefined)
    .sort((a, b) => b.mtime - a.mtime)
    .map((c) => c.path);
}

function readTokenFromFile(path: string): string | undefined {
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8")) as { token?: unknown };
    return typeof parsed.token === "string" && parsed.token.length > 0 ? parsed.token : undefined;
  } catch {
    return undefined;
  }
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  // `||` et non `??` : une option de plugin non renseignee arrive comme chaine vide.
  const bridgeUrl = (env.MCBRIDGE_URL || "http://127.0.0.1:25580").replace(/\/+$/, "");
  const transport = (env.MCBRIDGE_TRANSPORT || "stdio").toLowerCase();
  if (transport !== "stdio" && transport !== "http") {
    throw new Error(`MCBRIDGE_TRANSPORT doit valoir "stdio" ou "http", recu "${transport}"`);
  }

  let token: string | undefined = env.MCBRIDGE_TOKEN || undefined;
  let tokenSource = "MCBRIDGE_TOKEN";
  if (!token && env.MCBRIDGE_CONFIG) {
    token = readTokenFromFile(env.MCBRIDGE_CONFIG);
    tokenSource = env.MCBRIDGE_CONFIG;
  }
  if (!token) {
    for (const file of discoverConfigFiles()) {
      token = readTokenFromFile(file);
      if (token) {
        tokenSource = file;
        break;
      }
    }
  }
  if (!token) tokenSource = "aucun (definir MCBRIDGE_TOKEN ou MCBRIDGE_CONFIG)";

  return {
    bridgeUrl,
    token,
    tokenSource,
    timeoutMs: int(env.MCBRIDGE_TIMEOUT_MS, 120000),
    transport,
    httpPort: int(env.MCBRIDGE_HTTP_PORT, 25581),
  };
}
