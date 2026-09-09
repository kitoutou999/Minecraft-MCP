package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.focus.FocusState;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.TickWaiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * {@code info.status} : etat du bridge et du client, a appeler en premier.
 * Lecture hors thread principal (champs volatils ou immuables uniquement) pour rester reactif
 * meme si le jeu est bloque.
 */
public final class InfoHandlers {
	private InfoHandlers() {}

	public static void register(RpcRouter router) {
		router.register("info.status", ctx -> {
			BridgeConfig cfg = McBridgeMod.config();
			Minecraft mc = ClientMc.mc();
			JsonObject o = new JsonObject();
			o.addProperty("mod", McBridgeMod.MOD_ID);
			o.addProperty("modVersion", McBridgeMod.MOD_VERSION);
			o.addProperty("minecraftVersion", McBridgeMod.MC_VERSION);
			o.addProperty("inWorld", mc.player != null && mc.level != null);
			if (mc.player != null) o.addProperty("playerName", mc.player.getName().getString());
			ServerData sd = mc.getCurrentServer();
			if (sd != null) {
				o.addProperty("server", sd.ip);
				o.addProperty("serverName", sd.name);
			}
			o.addProperty("singleplayer", mc.hasSingleplayerServer());
			o.addProperty("tick", TickWaiter.currentTick());
			o.addProperty("focusActive", FocusState.INSTANCE.isActive());

			JsonObject caps = new JsonObject();
			caps.addProperty("vision", cfg.enableVision);
			caps.addProperty("commands", cfg.enableCommands);
			caps.addProperty("clientOptions", cfg.enableClientOptions);
			caps.addProperty("focus", cfg.enableFocus);
			caps.addProperty("reflection", cfg.enableReflection);
			o.add("capabilities", caps);
			o.add("methods", router.methodNames());
			return o;
		});
	}
}
