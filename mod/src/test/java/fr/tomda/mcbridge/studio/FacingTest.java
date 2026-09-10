package fr.tomda.mcbridge.studio;

import org.joml.Quaternionf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orientation d'un modele lue dans ses displays. Une erreur de signe ici donne un "front" qui
 * montre le dos du PNJ, et ne se voit qu'a l'oeil sur l'image.
 */
class FacingTest {

	private static final double EPS = 1e-3;

	private static Quaternionf aroundY(double degrees) {
		return new Quaternionf().rotationY((float) Math.toRadians(degrees));
	}

	/** Un os ModelEngine : rotation dans la transformation, yaw d'entite nul. */
	private static Facing.Sample bone(double degrees) {
		return new Facing.Sample(aroundY(degrees), 0);
	}

	/** Deux angles designent la meme direction a 360 pres : 180 et -180 sont le meme nord. */
	private static void assertDirection(double expected, double actual, String message) {
		double diff = Math.abs(Framing.wrapDegrees(actual - expected));
		assertTrue(diff < EPS, message + " : attendu " + expected + ", obtenu " + actual);
	}

	private static void assertDirection(double expected, double actual) {
		assertDirection(expected, actual, "direction");
	}

	@Test
	@DisplayName("un display en rotation identite et yaw nul regarde le sud, yaw 0")
	void identityFacesSouth() {
		assertEquals(0.0, Facing.rotationY(new Quaternionf()), EPS);
		assertDirection(0.0, Facing.yawOf(List.of(bone(0))));
	}

	@Test
	@DisplayName("une rotation autour de +Y se lit en sens direct, et le yaw en est l'oppose : calibre en jeu")
	void rotationMapsToYaw() {
		assertEquals(90.0, Facing.rotationY(aroundY(90)), EPS);
		assertEquals(-45.0, Facing.rotationY(aroundY(-45)), EPS);
		// Un modele tourne de 180 regarde le nord (yaw 180), de 90 l'est (yaw -90), de -90 l'ouest (yaw 90).
		assertDirection(180.0, Facing.yawOf(List.of(bone(180))));
		assertDirection(-90.0, Facing.yawOf(List.of(bone(90))));
		assertDirection(90.0, Facing.yawOf(List.of(bone(-90))));
		// Les deux PNJ de la calibration : -166 degres regardait le nord, -127 le nord-ouest.
		assertDirection(166.0, Facing.yawOf(List.of(bone(-166))), "PNJ tourne de -166 : nord, un peu vers l'ouest");
		assertDirection(127.0, Facing.yawOf(List.of(bone(-127))), "PNJ tourne de -127 : nord-ouest");
	}

	@Test
	@DisplayName("un meuble oriente par le yaw de son entite garde ce yaw, et une rotation s'y retranche")
	void entityYawCounts() {
		assertDirection(90.0, Facing.yawOf(List.of(new Facing.Sample(new Quaternionf(), 90))), "meuble Nexo tourne vers l'ouest");
		assertDirection(60.0, Facing.yawOf(List.of(new Facing.Sample(aroundY(30), 90))), "yaw d'entite moins rotation");
	}

	@Test
	@DisplayName("un os anime autour de son axe garde la rotation de corps lisible")
	void animatedBoneKeepsBodyYaw() {
		// Corps tourne de 30 degres, puis bras leve de 60 degres autour de X (ordre de ModelEngine).
		Quaternionf arm = aroundY(30).mul(new Quaternionf().rotationX((float) Math.toRadians(60)));
		assertEquals(30.0, Facing.rotationY(arm), EPS);
		assertTrue(Facing.weight(arm) < 1.0 && Facing.weight(arm) > 0.4, "un os incline pese moins dans la moyenne");
		// Un os pointe a la verticale ne dit rien de l'orientation.
		Quaternionf vertical = new Quaternionf().rotationX((float) Math.toRadians(90));
		assertTrue(Double.isNaN(Facing.rotationY(vertical)));
	}

	@Test
	@DisplayName("la moyenne est circulaire : deux os de part et d'autre de 180 ne donnent pas 0")
	void meanIsCircular() {
		assertDirection(180.0, Facing.circularMean(new double[]{170, -170}, null));
		assertEquals(10.0, Facing.circularMean(new double[]{0, 20, Double.NaN}, new double[]{1, 1, 5}), EPS, "NaN ignore, poids respecte");
		assertTrue(Double.isNaN(Facing.circularMean(new double[]{}, null)));
		assertTrue(Double.isNaN(Facing.circularMean(new double[]{0, 180}, null)), "deux directions opposees ne se moyennent pas");
	}

	@Test
	@DisplayName("sur un modele entier, la tete tournee ne fait pas basculer le corps")
	void wholeModelIsDominatedByBody() {
		List<Facing.Sample> bones = List.of(bone(-164), bone(-166), bone(-164), bone(-120) /* tete tournee */,
				new Facing.Sample(new Quaternionf().rotationX((float) Math.toRadians(90)), 0) /* os vertical, ignore */);
		double yaw = Facing.yawOf(bones);
		assertTrue(yaw > 145 && yaw < 170, "yaw " + yaw + " attendu autour de 155, entre le corps et la tete");
		assertTrue(Double.isNaN(Facing.yawOf(List.of())));
	}
}
