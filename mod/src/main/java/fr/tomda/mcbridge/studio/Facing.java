package fr.tomda.mcbridge.studio;

import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.List;

/**
 * Orientation d'un modele porte par des entites display, lue dans leurs transformations.
 *
 * <p>Un mob ModelEngine est une entite de base invisible plus un {@code item_display} par os. Le
 * yaw de la base ne veut rien dire (un {@code area_effect_cloud} reste a 0) et celui des displays
 * non plus : ModelEngine oriente le modele par la rotation gauche de la transformation de chaque
 * display, tous les os portant le meme angle autour de +Y, les os animes en plus de leur propre
 * rotation. Un meuble Nexo, lui, est un {@code item_display} oriente par son yaw d'entite.
 *
 * <p>Convention calibree en jeu le 2026-09-10 sur deux PNJ : un display en rotation identite et
 * yaw 0 regarde le sud (l'avant du modele est +Z), et une rotation de {@code a} degres autour de +Y
 * en sens direct le fait regarder vers le yaw {@code -a}. Le PNJ dont les os portaient -166 degres
 * regardait le nord, celui a -127 degres le nord-ouest. C'est coherent avec la chaine de rendu
 * ({@code DisplayRenderer} applique l'orientation {@code -yaw} de l'entite, puis la
 * transformation) et avec la convention Minecraft ou le yaw croit dans le sens horaire vu de
 * dessus : le yaw d'un display vaut donc son yaw d'entite moins {@code a}.
 *
 * <p>Classe sans dependance au jeu, testee hors Minecraft.
 */
public final class Facing {
	private Facing() {}

	/** En deca, le vecteur avant projete au sol est trop court pour donner une direction fiable. */
	private static final double MIN_HORIZONTAL = 0.2;

	/** Ce qu'un display dit de son orientation : sa rotation gauche et son yaw d'entite. */
	public record Sample(Quaternionfc rotation, double entityYaw) {}

	/**
	 * Angle de rotation autour de +Y, en degres et en sens direct, porte par un quaternion : on
	 * tourne le vecteur avant (+Z) et on lit sa direction au sol. Un os anime tourne en plus autour
	 * de son propre axe, mais sa rotation de corps reste lisible tant que l'avant ne pointe pas a la
	 * verticale.
	 *
	 * @return l'angle dans ]-180, 180], ou NaN si le vecteur avant est presque vertical
	 */
	public static double rotationY(Quaternionfc q) {
		Vector3f f = q.transform(new Vector3f(0, 0, 1));
		double horizontal = Math.sqrt(f.x * f.x + f.z * f.z);
		if (horizontal < MIN_HORIZONTAL) return Double.NaN;
		return Math.toDegrees(Math.atan2(f.x, f.z));
	}

	/** Poids d'une rotation dans la moyenne : la longueur au sol du vecteur avant tourne. */
	public static double weight(Quaternionfc q) {
		Vector3f f = q.transform(new Vector3f(0, 0, 1));
		return Math.sqrt(f.x * f.x + f.z * f.z);
	}

	/** Yaw Minecraft d'un display : son yaw d'entite moins la rotation de sa transformation. */
	public static double yawOf(double rotationY, double entityYaw) {
		return Framing.wrapDegrees(entityYaw - rotationY);
	}

	/**
	 * Moyenne circulaire ponderee d'angles en degres. Les angles NaN et les poids nuls sont
	 * ignores.
	 *
	 * @return l'angle moyen dans ]-180, 180], ou NaN si rien n'est exploitable
	 */
	public static double circularMean(double[] degrees, double[] weights) {
		double sx = 0;
		double sy = 0;
		for (int i = 0; i < degrees.length; i++) {
			double w = weights == null ? 1.0 : weights[i];
			if (Double.isNaN(degrees[i]) || !(w > 0)) continue;
			double r = Math.toRadians(degrees[i]);
			sx += w * Math.cos(r);
			sy += w * Math.sin(r);
		}
		if (Math.sqrt(sx * sx + sy * sy) < 1e-9) return Double.NaN;
		return Framing.wrapDegrees(Math.toDegrees(Math.atan2(sy, sx)));
	}

	/**
	 * Yaw Minecraft d'un modele a partir de ses displays : moyenne circulaire des yaws de chaque
	 * display, ponderee par la lisibilite de sa rotation.
	 *
	 * @return le yaw dans ]-180, 180], ou NaN si aucun display n'est exploitable
	 */
	public static double yawOf(List<Sample> samples) {
		double[] yaws = new double[samples.size()];
		double[] weights = new double[samples.size()];
		for (int i = 0; i < samples.size(); i++) {
			Sample s = samples.get(i);
			double a = rotationY(s.rotation());
			yaws[i] = Double.isNaN(a) ? Double.NaN : yawOf(a, s.entityYaw());
			weights[i] = weight(s.rotation());
		}
		return circularMean(yaws, weights);
	}
}
