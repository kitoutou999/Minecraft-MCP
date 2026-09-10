package fr.tomda.mcbridge.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.RpcContext;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.Rcon;
import net.minecraft.client.multiplayer.ServerData;


/**
 * Ce que seul le serveur sait, via RCON.
 *
 * <p>Le mod ne voit que ce que le client recoit : les entites a portee, les objets d'un menu
 * ouvert, le monde charge. Tout le reste appartient au serveur : le catalogue d'objets d'un plugin,
 * la liste de ses mobs, les joueurs hors ligne, le contenu d'un monde lointain. RCON ouvre cette
 * moitie, et rend en prime la sortie des commandes, qu'une commande envoyee en tant que joueur ne
 * laisse jamais voir autrement que dans le chat.
 *
 * <p>Ces outils n'existent que si RCON est configure. Sans lui, ils expliquent comment l'activer
 * plutot que d'echouer sans raison lisible.
 */
public final class ServerHandlers {
	private ServerHandlers() {}

	public static void register(RpcRouter router) {
		router.register("server.status", ctx -> {
			BridgeConfig.Rcon cfg = McBridgeMod.config().rcon;
			JsonObject o = new JsonObject();
			o.addProperty("rconEnabled", cfg.enabled);
			o.addProperty("rconConfigured", cfg.enabled && cfg.password != null && !cfg.password.isBlank());
			o.addProperty("host", cfg.host);
			o.addProperty("port", cfg.port);

			ServerData sd = ClientMc.call(() -> ClientMc.mc().getCurrentServer());
			if (sd != null) o.addProperty("joinedServer", sd.ip);

			boolean reachable = Rcon.available();
			o.addProperty("rconReachable", reachable);
			if (!reachable) {
				o.addProperty("hint", cfg.enabled
						? "RCON est active mais injoignable : verifier l'adresse, le port, le mot de passe, et "
						+ "enable-rcon dans server.properties."
						: "RCON est desactive. Renseigner le bloc rcon de mcbridge.json (enabled, host, port, password) "
						+ "pour que les teleportations et changements de mode passent par la console, et pour ouvrir "
						+ "les outils server.*.");
				return o;
			}

			// Sans RCON ces trois informations seraient invisibles depuis le client.
			String version = Rcon.tryRun("version");
			if (version != null) o.addProperty("version", version.trim());
			String list = Rcon.tryRun("list");
			if (list != null) o.addProperty("players", list.trim());
			if (ctx.optBoolean("includePlugins", false)) {
				String plugins = Rcon.tryRun("plugins");
				if (plugins != null) o.addProperty("plugins", plugins.trim());
			}
			return o;
		});

		router.register("server.command", ctx -> {
			BridgeConfig.Rcon cfg = McBridgeMod.config().rcon;
			String command = ctx.getString("command").trim();
			if (command.startsWith("/")) command = command.substring(1);
			if (command.isEmpty()) throw RpcException.badRequest("Commande vide.");

			// Toutes les tetes de commande sont examinees, pas seulement la premiere : 'execute run stop'
			// arrete le serveur sans commencer par 'stop', et 'minecraft:stop' le designe autrement.
			String blocked = CommandGuard.firstBlocked(command, cfg.blockedCommands);
			if (blocked != null) {
				throw RpcException.forbidden("Commande '" + blocked + "' refusee : elle figure dans blockedCommands de "
						+ "mcbridge.json. Rien ne rattrape un serveur arrete par erreur.");
			}

			if (!Rcon.available()) {
				throw RpcException.unavailable("RCON n'est pas disponible. Renseigner le bloc rcon de mcbridge.json "
						+ "(enabled, host, port, password). Sans lui, une commande peut toujours etre envoyee en tant "
						+ "que joueur avec chat.send, mais sa sortie n'est pas lisible et le compte doit avoir la permission.");
			}
			String output = Rcon.tryRun(command);
			if (output == null) throw new RpcException("rcon_failed", "La commande n'a pas abouti : connexion RCON perdue.");

			JsonObject o = new JsonObject();
			o.addProperty("command", command);
			o.addProperty("output", output);
			JsonArray lines = new JsonArray();
			for (String line : output.split("\\r?\\n")) {
				if (!line.isBlank()) lines.add(line);
			}
			o.add("lines", lines);
			o.addProperty("lineCount", lines.size());
			return o;
		});
	}
}
