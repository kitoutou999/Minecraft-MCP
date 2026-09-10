package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.util.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Options client utiles aux captures : HUD, FOV, echelle de l'interface, distance de rendu, gamma.
 * Les valeurs sont appliquees via {@code OptionInstance.set}, comme depuis le menu Options.
 */
public final class ClientOptionHandlers {
	private ClientOptionHandlers() {}

	public static void register(RpcRouter router) {
		router.register("client.getOptions", ctx -> ClientMc.call(ClientOptionHandlers::snapshot));

		router.registerExclusive("client.setOptions", ctx -> {
			if (!McBridgeMod.config().enableClientOptions) {
				throw RpcException.unavailable("La modification des options est desactivee (enableClientOptions=false).");
			}
			return ClientMc.call(() -> {
				Options opt = ClientMc.mc().options;
				Boolean hideGui = ctx.optBooleanOrNull("hideGui");
				if (hideGui != null) opt.hideGui = hideGui;
				if (ctx.has("fov")) opt.fov().set(Math.max(30, Math.min(110, ctx.getInt("fov"))));
				if (ctx.has("guiScale")) opt.guiScale().set(Math.max(0, Math.min(8, ctx.getInt("guiScale"))));
				if (ctx.has("renderDistance")) opt.renderDistance().set(Math.max(2, Math.min(64, ctx.getInt("renderDistance"))));
				if (ctx.has("gamma")) opt.gamma().set(Math.max(0.0, Math.min(1.0, ctx.getDouble("gamma"))));
				return snapshot();
			});
		});
	}

	private static JsonObject snapshot() {
		Minecraft mc = ClientMc.mc();
		Options opt = mc.options;
		JsonObject o = new JsonObject();
		o.addProperty("hideGui", opt.hideGui);
		o.addProperty("fov", opt.fov().get());
		o.addProperty("guiScale", opt.guiScale().get());
		o.addProperty("renderDistance", opt.renderDistance().get());
		o.addProperty("gamma", opt.gamma().get());
		o.addProperty("windowWidth", mc.getWindow().getWidth());
		o.addProperty("windowHeight", mc.getWindow().getHeight());
		return o;
	}
}
