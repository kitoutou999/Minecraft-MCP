package fr.tomda.mcbridge.studio;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Geometrie du cadrage : ou placer la camera pour qu'un volume tienne dans l'image.
 *
 * <p>Isole du reste du mode studio parce que c'est du calcul pur, sans etat de jeu : c'est la
 * partie ou une erreur de signe ou d'axe se voit le moins et coute le plus cher, un sujet coupe ou
 * minuscule dans chaque image produite.
 */
public final class Framing {
	private Framing() {}

	/**
	 * Vecteur de visee pour un couple (yaw, pitch) en degres, convention Minecraft :
	 * yaw 0 regarde vers le sud (+Z), 90 vers l'ouest (-X), pitch positif vers le bas.
	 */
	public static Vec3 lookVector(double yawDeg, double pitchDeg) {
		double y = Math.toRadians(yawDeg);
		double p = Math.toRadians(pitchDeg);
		return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/** Vecteur unitaire vers la droite de l'ecran pour un yaw donne (toujours horizontal). */
	public static Vec3 rightVector(double yawDeg) {
		double yr = Math.toRadians(yawDeg);
		return new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
	}

	/**
	 * Distance minimale pour que toute la boite tienne dans l'image.
	 *
	 * <p>Une sphere englobante suffirait, mais elle gaspille beaucoup de place pour un sujet plat ou
	 * allonge, et ignore le fait qu'un ecran large offre un champ horizontal bien plus grand que
	 * vertical. On projette donc les huit coins de la boite dans le repere de la camera : pour
	 * chaque coin, la contrainte {@code |lateral| <= profondeur * tan(demi-champ)} donne une
	 * distance minimale, et on retient la plus grande. La marge elargit le champ utile, ce qui
	 * laisse de l'air autour du sujet.
	 *
	 * @param box    volume a cadrer, en coordonnees monde
	 * @param center point vise, autour duquel la camera tourne
	 * @param fovDeg champ de vision vertical en degres
	 * @param aspect largeur sur hauteur de l'image
	 * @param margin marge multiplicative (1.0 = ajuste au plus juste)
	 */
	public static double fitDistance(AABB box, Vec3 center, double yaw, double pitch,
	                                 double fovDeg, double aspect, double margin) {
		Vec3 look = lookVector(yaw, pitch);
		Vec3 right = rightVector(yaw);
		Vec3 up = right.cross(look);
		double m = Math.max(0.5, margin);
		double tanV = Math.tan(Math.toRadians(fovDeg) / 2.0) / m;
		double tanH = tanV * Math.max(0.2, aspect);

		double[] xs = {box.minX - center.x, box.maxX - center.x};
		double[] ys = {box.minY - center.y, box.maxY - center.y};
		double[] zs = {box.minZ - center.z, box.maxZ - center.z};
		double needed = 0;
		for (double qx : xs) {
			for (double qy : ys) {
				for (double qz : zs) {
					Vec3 q = new Vec3(qx, qy, qz);
					double depth = q.dot(look);
					needed = Math.max(needed, Math.abs(q.dot(right)) / tanH - depth);
					needed = Math.max(needed, Math.abs(q.dot(up)) / tanV - depth);
				}
			}
		}
		return needed;
	}

	/** Ramene un angle dans l'intervalle [-180, 180[. */
	public static double wrapDegrees(double deg) {
		double d = deg % 360.0;
		if (d >= 180.0) d -= 360.0;
		if (d < -180.0) d += 360.0;
		return d;
	}
}
