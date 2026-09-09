package fr.tomda.mcbridge.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Historique borne des messages recus (chat des joueurs et messages systeme du serveur). */
public final class ChatLog {
	private static final int CAPACITY = 500;
	private final Deque<JsonObject> ring = new ArrayDeque<>();

	public void add(String kind, String text, String sender) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind);
		o.addProperty("text", text);
		if (sender != null) o.addProperty("sender", sender);
		o.addProperty("timestampMs", System.currentTimeMillis());
		synchronized (ring) {
			ring.addLast(o);
			while (ring.size() > CAPACITY) ring.removeFirst();
		}
	}

	/** Les {@code limit} derniers messages, du plus ancien au plus recent. */
	public JsonArray recent(int limit) {
		List<JsonObject> all;
		synchronized (ring) {
			all = new ArrayList<>(ring);
		}
		JsonArray out = new JsonArray();
		int start = Math.max(0, all.size() - limit);
		for (int i = start; i < all.size(); i++) out.add(all.get(i));
		return out;
	}
}
