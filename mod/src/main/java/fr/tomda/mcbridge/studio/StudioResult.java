package fr.tomda.mcbridge.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Forme compacte du resultat de {@code studio.frameTarget}.
 *
 * <p>Le resultat complet decrit tout le cadrage : boites de mesure, liste des parties, passes de
 * reglage, plan de chaque vue. Mesure sur un cas reel, c'est six fois plus de texte que l'image
 * elle-meme n'en coute, et ce texte est relu par le modele a chaque tour suivant. Par defaut on ne
 * garde que ce qui sert apres coup : reconnaitre la cible, savoir d'ou chaque photo a ete prise,
 * juger si le sujet remplit l'image, et ce qui a ete restaure. Le detail reste disponible avec
 * {@code verbose}.
 *
 * <p>Classe sans dependance au jeu : une simple transformation de JSON, testee hors Minecraft.
 */
public final class StudioResult {
	private StudioResult() {}

	private static final List<String> TARGET_KEYS = List.of("id", "uuid", "type", "name", "customName", "pos");
	private static final List<String> IMAGE_KEYS = List.of("format", "width", "height", "bytes", "base64");

	/** Resume d'un resultat complet ; l'objet d'origine n'est pas modifie. */
	public static JsonObject compact(JsonObject full) {
		JsonObject o = new JsonObject();

		JsonObject target = new JsonObject();
		JsonObject t = full.has("target") && full.get("target").isJsonObject() ? full.getAsJsonObject("target") : null;
		if (t != null) for (String k : TARGET_KEYS) if (t.has(k)) target.add(k, t.get(k));
		o.add("target", target);

		JsonObject bounds = full.has("bounds") && full.get("bounds").isJsonObject() ? full.getAsJsonObject("bounds") : null;
		if (bounds != null) {
			if (bounds.has("parts") && bounds.get("parts").isJsonArray()) {
				o.addProperty("partCount", bounds.getAsJsonArray("parts").size());
			}
			if (bounds.has("center")) o.add("center", bounds.get("center"));
			if (bounds.has("box") && bounds.get("box").isJsonObject() && bounds.getAsJsonObject("box").has("size")) {
				o.add("size", bounds.getAsJsonObject("box").get("size"));
			}
		}
		if (full.has("fov")) o.add("fov", full.get("fov"));

		JsonArray shots = new JsonArray();
		if (full.has("shots") && full.get("shots").isJsonArray()) {
			for (JsonElement e : full.getAsJsonArray("shots")) {
				if (!e.isJsonObject()) continue;
				JsonObject s = e.getAsJsonObject();
				JsonObject c = new JsonObject();
				if (s.has("angle") && s.get("angle").isJsonObject() && s.getAsJsonObject("angle").has("name")) {
					c.add("angle", s.getAsJsonObject("angle").get("name"));
				}
				// La camera reellement utilisee, apres reglage de la distance ; a defaut celle du plan.
				if (s.has("cameraUsed")) c.add("camera", s.get("cameraUsed"));
				else if (s.has("camera")) c.add("camera", s.get("camera"));
				// Part de l'image occupee par le sujet ; negatif quand rien n'a pu etre mesure.
				if (s.has("refineFill") && s.get("refineFill").getAsDouble() >= 0) c.add("fill", s.get("refineFill"));
				for (String k : IMAGE_KEYS) if (s.has(k)) c.add(k, s.get(k));
				shots.add(c);
			}
		}
		o.add("shots", shots);

		JsonObject spectator = full.has("spectator") && full.get("spectator").isJsonObject()
				? full.getAsJsonObject("spectator") : null;
		if (spectator != null && spectator.has("switched") && spectator.get("switched").getAsBoolean()
				&& spectator.has("previousMode")) {
			o.add("previousGameMode", spectator.get("previousMode"));
		}
		if (full.has("returnedToStart")) o.add("returnedToStart", full.get("returnedToStart"));
		if (full.has("captured")) o.add("captured", full.get("captured"));
		return o;
	}
}
