package fr.tomda.mcbridge.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.SseHub;
import fr.tomda.mcbridge.util.TickWaiter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tampon circulaire des evenements recents (lecture par {@code events.poll}) et diffusion SSE.
 *
 * <p>Types emis par le mod : {@code chat}, {@code system_message}, {@code join}, {@code disconnect},
 * {@code focus_changed}, {@code resources_reloaded}. Un handler peut en emettre d'autres.
 */
public final class EventBus {
	private static final int CAPACITY = 2000;
	private final SseHub sse;
	private final AtomicLong seq = new AtomicLong();
	private final Deque<GameEvent> ring = new ArrayDeque<>();

	public EventBus(SseHub sse) {
		this.sse = sse;
	}

	public GameEvent emit(String type, JsonObject data) {
		GameEvent e = new GameEvent(seq.incrementAndGet(), type, TickWaiter.currentTick(), System.currentTimeMillis(), data);
		synchronized (ring) {
			ring.addLast(e);
			while (ring.size() > CAPACITY) ring.removeFirst();
		}
		try {
			sse.broadcast(type, Json.GSON.toJson(e.toJson()));
		} catch (Throwable ignored) {
			// la diffusion ne doit jamais casser le thread de jeu
		}
		return e;
	}

	public long lastId() {
		return seq.get();
	}

	/** Evenements d'id strictement superieur a {@code sinceId}, filtres par type, du plus ancien au plus recent. */
	public JsonArray recent(int limit, Collection<String> typeFilter, long sinceId) {
		GameEvent[] snapshot;
		synchronized (ring) {
			snapshot = ring.toArray(new GameEvent[0]);
		}
		boolean filter = typeFilter != null && !typeFilter.isEmpty();
		List<JsonObject> picked = new ArrayList<>();
		for (int i = snapshot.length - 1; i >= 0 && picked.size() < limit; i--) {
			GameEvent e = snapshot[i];
			if (e.id() <= sinceId) continue;
			if (filter && !typeFilter.contains(e.type())) continue;
			picked.add(e.toJson());
		}
		JsonArray out = new JsonArray();
		for (int i = picked.size() - 1; i >= 0; i--) out.add(picked.get(i));
		return out;
	}
}
