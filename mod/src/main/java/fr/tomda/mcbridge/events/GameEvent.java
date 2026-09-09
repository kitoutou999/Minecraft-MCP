package fr.tomda.mcbridge.events;

import com.google.gson.JsonObject;

/** Un evenement de jeu horodate en ticks client, avec un identifiant croissant pour la reprise. */
public record GameEvent(long id, String type, long tick, long timestampMs, JsonObject data) {
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("type", type);
		o.addProperty("tick", tick);
		o.addProperty("timestampMs", timestampMs);
		o.add("data", data == null ? new JsonObject() : data);
		return o;
	}
}
