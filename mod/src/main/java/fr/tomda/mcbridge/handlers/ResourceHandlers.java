package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.events.EventBus;
import fr.tomda.mcbridge.util.ClientMc;

import java.util.concurrent.CompletableFuture;

/**
 * {@code resources.reload} : equivalent de F3+T. Recharge packs de ressources, modeles, textures
 * et shaders. Indispensable apres une modification du pack Nexo ou d'un modele Blockbench.
 * Le rechargement est lance sur le thread de rendu et attendu hors de celui-ci.
 */
public final class ResourceHandlers {
	private ResourceHandlers() {}

	public static void register(RpcRouter router, EventBus events) {
		router.registerExclusive("resources.reload", ctx -> {
			long start = System.currentTimeMillis();
			CompletableFuture<Void> reload = ClientMc.call(() -> ClientMc.mc().reloadResourcePacks());
			MainThread.await(reload, McBridgeMod.config().reloadTimeoutMs);
			long duration = System.currentTimeMillis() - start;
			JsonObject o = new JsonObject();
			o.addProperty("reloaded", true);
			o.addProperty("durationMs", duration);
			events.emit("resources_reloaded", o.deepCopy());
			return o;
		});
	}
}
