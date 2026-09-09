package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.util.TickWaiter;

/** {@code game.waitTicks} : temporisation en ticks client (20 par seconde), 600 max par appel. */
public final class GameHandlers {
	private GameHandlers() {}

	public static void register(RpcRouter router) {
		router.register("game.waitTicks", ctx -> {
			int ticks = Math.max(1, Math.min(ctx.optInt("ticks", 20), 600));
			long before = TickWaiter.currentTick();
			MainThread.await(TickWaiter.after(ticks), ticks * 50L + 5000L);
			JsonObject o = new JsonObject();
			o.addProperty("waitedTicks", ticks);
			o.addProperty("tickBefore", before);
			o.addProperty("tickAfter", TickWaiter.currentTick());
			return o;
		});
	}
}
