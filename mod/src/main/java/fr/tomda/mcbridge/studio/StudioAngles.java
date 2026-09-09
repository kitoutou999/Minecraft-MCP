package fr.tomda.mcbridge.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Angles de prise de vue.
 *
 * <p>Un angle est un couple (azimut, inclinaison) en degres. L'azimut est relatif a l'orientation
 * de la cible : 0 place la camera devant elle (on voit sa face avant), 180 derriere, 90 et -90 sur
 * les cotes. L'inclinaison positive place la camera au-dessus et regarde vers le bas.
 * Avec {@code absolute}, l'azimut est un yaw monde et ne depend plus de la cible.
 */
public record StudioAngles(String name, double azimuth, double pitch) {

	/** Vues nommees disponibles pour le parametre {@code angles}. */
	public static StudioAngles preset(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "front", "face" -> new StudioAngles("front", 0, 0);
			case "back", "dos" -> new StudioAngles("back", 180, 0);
			case "left", "gauche" -> new StudioAngles("left", 90, 0);
			case "right", "droite" -> new StudioAngles("right", -90, 0);
			case "top", "dessus" -> new StudioAngles("top", 0, 80);
			case "bottom", "dessous" -> new StudioAngles("bottom", 0, -80);
			case "iso", "isometric" -> new StudioAngles("iso", -45, 30);
			case "iso_left" -> new StudioAngles("iso_left", 45, 30);
			case "three_quarter", "34" -> new StudioAngles("three_quarter", -30, 15);
			default -> null;
		};
	}

	public static List<String> presetNames() {
		return List.of("front", "back", "left", "right", "top", "bottom", "iso", "iso_left", "three_quarter");
	}

	/** {@code steps} vues reparties sur 360 degres, a inclinaison constante. */
	public static List<StudioAngles> turntable(int steps, double pitch) {
		List<StudioAngles> out = new ArrayList<>();
		for (int i = 0; i < steps; i++) {
			double az = 360.0 * i / steps;
			out.add(new StudioAngles(String.format(Locale.ROOT, "turn_%03d", Math.round(az)), az, pitch));
		}
		return out;
	}
}
