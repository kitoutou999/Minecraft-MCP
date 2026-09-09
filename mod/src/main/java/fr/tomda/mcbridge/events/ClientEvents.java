package fr.tomda.mcbridge.events;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Branche les evenements Fabric API sur le bus et l'historique de chat.
 * Pas de mixin necessaire : Fabric API expose deja la reception des messages.
 */
public final class ClientEvents {
	private ClientEvents() {}

	public static void register(EventBus events, ChatLog chat) {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String text = message.getString();
			chat.add(overlay ? "overlay" : "system", text, null);
			JsonObject d = new JsonObject();
			d.addProperty("text", text);
			d.addProperty("overlay", overlay);
			events.emit("system_message", d);
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			String text = message.getString();
			String senderName = sender != null ? sender.name() : null;
			chat.add("chat", text, senderName);
			JsonObject d = new JsonObject();
			d.addProperty("text", text);
			if (senderName != null) d.addProperty("sender", senderName);
			events.emit("chat", d);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			JsonObject d = new JsonObject();
			if (handler.getServerData() != null) d.addProperty("server", handler.getServerData().ip);
			events.emit("join", d);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> events.emit("disconnect", new JsonObject()));
	}
}
