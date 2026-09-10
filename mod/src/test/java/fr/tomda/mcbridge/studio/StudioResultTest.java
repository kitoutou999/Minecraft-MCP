package fr.tomda.mcbridge.studio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.tomda.mcbridge.bridge.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resume du resultat de frame_target : c'est le texte que le modele lit apres chaque prise de vue,
 * et qu'il repaie a chaque tour suivant. Le resume doit garder de quoi reconnaitre la cible et
 * reproduire la vue, et perdre tout le reste.
 */
class StudioResultTest {

	/** Forme reelle d'un resultat complet, reduite a deux parties et une vue. */
	private static JsonObject full() {
		return JsonParser.parseString("""
			{
			  "target": {"id": 654, "uuid": "64e0fbd2-adc5-456c-9b6a-3910e727e6a2", "type": "minecraft:area_effect_cloud",
			             "name": "Area Effect Cloud", "pos": {"x": 78.5, "y": 85.5, "z": 84.5}, "yaw": 0, "pitch": 0,
			             "isDisplay": false, "invisible": true, "distance": 13.59, "passengerIds": [655, 656],
			             "boundingBox": {"min": {"x": 78.5, "y": 85.5, "z": 84.5}, "max": {"x": 78.5, "y": 86, "z": 84.5}}},
			  "bounds": {"box": {"min": {"x": 77.98, "y": 85.5, "z": 83.98}, "max": {"x": 79.02, "y": 88.3, "z": 85.02},
			                     "size": {"x": 1.035, "y": 2.8, "z": 1.035}},
			             "center": {"x": 78.5, "y": 86.9, "z": 84.5}, "radius": 1.8167, "targetYaw": 0,
			             "parts": [{"id": 654, "type": "minecraft:area_effect_cloud", "boundsSource": "hitbox"},
			                       {"id": 655, "type": "minecraft:interaction", "boundsSource": "hitbox"},
			                       {"id": 656, "type": "minecraft:item_display", "boundsSource": "culling"}]},
			  "fov": 60, "aspect": 1.7778,
			  "shots": [{
			    "angle": {"name": "front", "azimuth": 0, "pitch": 0},
			    "camera": {"pos": {"x": 78.5, "y": 86.9, "z": 90.2}, "yaw": 180, "pitch": 0, "distance": 5.7},
			    "refine": [{"pass": 0, "fill": 0.55, "distance": 4.3, "reason": "sujet trop petit, approche"},
			               {"pass": 1, "fill": 0.79, "distance": 4.3, "reason": "cadrage correct"}],
			    "refineFill": 0.79, "distanceUsed": 4.3,
			    "cameraUsed": {"pos": {"x": 78.5, "y": 86.9, "z": 88.8}, "yaw": 180, "pitch": 0, "distance": 4.3},
			    "format": "jpeg", "mimeType": "image/jpeg", "width": 640, "height": 402,
			    "sourceWidth": 1920, "sourceHeight": 1080, "crop": {"x": 700, "y": 120, "width": 520, "height": 830},
			    "bytes": 6206, "base64": "AAAA"
			  }],
			  "eyeHeight": 1.62, "studio": true,
			  "spectator": {"requested": true, "applied": true, "switched": true, "previousMode": "survival"},
			  "previousFov": 70, "returnedToStart": true, "captured": true
			}
			""").getAsJsonObject();
	}

	@Test
	@DisplayName("le resume garde la cible, la mesure, la camera reelle et l'image de chaque vue")
	void keepsWhatMatters() {
		JsonObject c = StudioResult.compact(full());

		JsonObject target = c.getAsJsonObject("target");
		assertEquals(654, target.get("id").getAsInt());
		assertEquals("minecraft:area_effect_cloud", target.get("type").getAsString());
		assertEquals(78.5, target.getAsJsonObject("pos").get("x").getAsDouble(), 1e-9);
		assertFalse(target.has("boundingBox"), "les boites de mesure ne servent plus une fois la photo prise");

		assertEquals(3, c.get("partCount").getAsInt());
		assertEquals(2.8, c.getAsJsonObject("size").get("y").getAsDouble(), 1e-9);
		assertEquals(86.9, c.getAsJsonObject("center").get("y").getAsDouble(), 1e-9);
		assertEquals(60, c.get("fov").getAsInt());

		JsonObject shot = c.getAsJsonArray("shots").get(0).getAsJsonObject();
		assertEquals("front", shot.get("angle").getAsString(), "le nom suffit a designer la vue");
		JsonObject camera = shot.getAsJsonObject("camera");
		assertEquals(4.3, camera.get("distance").getAsDouble(), 1e-9, "la distance reglee, pas celle du plan");
		assertEquals(88.8, camera.getAsJsonObject("pos").get("z").getAsDouble(), 1e-9, "la position reellement utilisee");
		assertEquals(0.79, shot.get("fill").getAsDouble(), 1e-9);
		assertEquals(640, shot.get("width").getAsInt());
		assertEquals("AAAA", shot.get("base64").getAsString(), "l'image elle-meme passe intacte");
		for (String gone : new String[]{"refine", "sourceWidth", "crop", "mimeType", "distanceUsed", "cameraUsed"}) {
			assertFalse(shot.has(gone), gone + " ne fait pas partie du resume");
		}

		assertEquals("survival", c.get("previousGameMode").getAsString());
		assertTrue(c.get("returnedToStart").getAsBoolean());
		assertTrue(c.get("captured").getAsBoolean());
		for (String gone : new String[]{"bounds", "aspect", "eyeHeight", "studio", "spectator", "previousFov"}) {
			assertFalse(c.has(gone), gone + " ne fait pas partie du resume");
		}
	}

	@Test
	@DisplayName("le resume tient dans moins de la moitie du texte complet, meme sur un petit cas")
	void isMuchShorter() {
		// Sur un cas reel (neuf parties, passes de reglage), le rapport mesure etait de un a six.
		JsonObject f = full();
		int before = Json.GSON.toJson(f).length();
		int after = Json.GSON.toJson(StudioResult.compact(f)).length();
		assertTrue(after * 2 < before, "resume " + after + " caracteres contre " + before);
	}

	@Test
	@DisplayName("sans reglage de distance ni passage en spectateur, le resume s'en tient au plan")
	void fallsBackToPlan() {
		JsonObject f = full();
		JsonObject shot = f.getAsJsonArray("shots").get(0).getAsJsonObject();
		shot.remove("cameraUsed");
		shot.addProperty("refineFill", -1);
		f.getAsJsonObject("spectator").addProperty("switched", false);

		JsonObject c = StudioResult.compact(f);
		JsonObject s = c.getAsJsonArray("shots").get(0).getAsJsonObject();
		assertEquals(5.7, s.getAsJsonObject("camera").get("distance").getAsDouble(), 1e-9, "a defaut, la camera du plan");
		assertFalse(s.has("fill"), "un remplissage negatif veut dire non mesure");
		assertFalse(c.has("previousGameMode"), "rien a rendre si le mode n'a pas change");
		assertTrue(f.getAsJsonArray("shots").get(0).getAsJsonObject().has("refine"), "l'original n'est pas modifie");
	}
}
