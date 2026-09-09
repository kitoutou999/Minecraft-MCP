package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.events.EventBus;

import java.util.HashSet;
import java.util.Set;

/** {@code events.poll} : lecture des evenements recents, avec reprise par {@code sinceId}. */
public final class EventHandlers {
	private EventHandlers() {}

	public static void register(RpcRouter router, EventBus events) {
		router.register("events.poll", ctx -> {
			int limit = Math.max(1, Math.min(ctx.optInt("limit", 100), 1000));
			long sinceId = (long) ctx.optDouble("sinceId", 0);
			Set<String> types = new HashSet<>();
			JsonArray t = ctx.optArray("types");
			if (t != null) t.forEach(x -> types.add(x.getAsString()));
			JsonObject o = new JsonObject();
			o.add("events", events.recent(limit, types, sinceId));
			o.addProperty("lastId", events.lastId());
			return o;
		});
	}
}
