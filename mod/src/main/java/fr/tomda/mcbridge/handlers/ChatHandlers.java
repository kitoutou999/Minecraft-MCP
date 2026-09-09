package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.events.ChatLog;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.Commands;
import net.minecraft.client.player.LocalPlayer;

/**
 * Chat et commandes en tant que joueur local.
 *
 * <p>Un message commencant par {@code /} est envoye comme commande : c'est le moyen prevu pour
 * teleporter, changer de mode de jeu, faire apparaitre une entite... Le joueur doit avoir les
 * permissions correspondantes sur le serveur (OP ou LuckPerms).
 */
public final class ChatHandlers {
	private ChatHandlers() {}

	public static void register(RpcRouter router, ChatLog chat) {
		router.register("chat.send", ctx -> {
			String message = ctx.getString("message");
			boolean isCommand = message.startsWith("/");
			// Forcer l'implementation vanilla d'une commande reprise par un plugin (EssentialsX
			// redefinit /tp, /gamemode, /time, /weather... avec une autre syntaxe).
			if (isCommand && ctx.optBoolean("vanilla", false) && !message.substring(1).contains(":")) {
				message = "/minecraft:" + message.substring(1);
			}
			final String toSend = message;
			if (isCommand && !McBridgeMod.config().enableCommands) {
				throw RpcException.unavailable("Les commandes sont desactivees (enableCommands=false).");
			}
			// Meme limiteur de debit que les commandes internes : le serveur compte tout envoi.
			if (isCommand) Commands.send(toSend.substring(1));
			return ClientMc.call(() -> {
				LocalPlayer p = ClientMc.player();
				if (!isCommand) p.connection.sendChat(toSend);
				JsonObject o = new JsonObject();
				o.addProperty("sent", true);
				o.addProperty("asCommand", isCommand);
				o.addProperty("command", toSend);
				return o;
			});
		});

		router.register("chat.recent", ctx -> {
			int limit = Math.max(1, Math.min(ctx.optInt("limit", 50), 500));
			JsonObject o = new JsonObject();
			o.add("messages", chat.recent(limit));
			return o;
		});
	}
}
