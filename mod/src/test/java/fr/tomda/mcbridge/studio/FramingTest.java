package fr.tomda.mcbridge.studio;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cadrage : le calcul qui decide ou se place la camera. Une erreur ici donne un sujet coupe ou
 * minuscule dans chaque image produite, et ne se voit qu'a l'oeil.
 */
class FramingTest {

	private static final double EPS = 1e-6;

	/** Le sujet reste entierement dans le champ vu depuis la distance calculee. */
	private static void assertFits(AABB box, Vec3 center, double yaw, double pitch,
	                               double fov, double aspect, double margin) {
		double distance = Framing.fitDistance(box, center, yaw, pitch, fov, aspect, margin);
		Vec3 look = Framing.lookVector(yaw, pitch);
		Vec3 right = Framing.rightVector(yaw);
		Vec3 up = right.cross(look);
		Vec3 camera = center.subtract(look.scale(distance));
		double tanV = Math.tan(Math.toRadians(fov) / 2.0) / Math.max(0.5, margin);
		double tanH = tanV * aspect;
		for (double x : new double[]{box.minX, box.maxX}) {
			for (double y : new double[]{box.minY, box.maxY}) {
				for (double z : new double[]{box.minZ, box.maxZ}) {
					Vec3 q = new Vec3(x, y, z).subtract(camera);
					double depth = q.dot(look);
					assertTrue(depth > 0, "coin derriere la camera");
					// Tolerance relative : le coin le plus contraignant tombe pile sur le bord.
					assertTrue(Math.abs(q.dot(right)) <= depth * tanH + 1e-9, "coin hors champ horizontal");
					assertTrue(Math.abs(q.dot(up)) <= depth * tanV + 1e-9, "coin hors champ vertical");
				}
			}
		}
	}

	@Test
	@DisplayName("yaw 0 regarde vers le sud, yaw 90 vers l'ouest, pitch positif vers le bas")
	void lookVectorFollowsMinecraftConvention() {
		Vec3 south = Framing.lookVector(0, 0);
		assertEquals(0, south.x, EPS);
		assertEquals(0, south.y, EPS);
		assertEquals(1, south.z, EPS);

		Vec3 west = Framing.lookVector(90, 0);
		assertEquals(-1, west.x, EPS);
		assertEquals(0, west.z, EPS);

		assertEquals(-1, Framing.lookVector(0, 90).y, EPS, "pitch 90 regarde vers le bas");
	}

	@Test
	@DisplayName("le repere ecran est orthonorme quel que soit l'angle")
	void screenBasisIsOrthonormal() {
		for (double yaw : new double[]{-180, -90, -33, 0, 45, 120, 179}) {
			for (double pitch : new double[]{-80, -15, 0, 30, 80}) {
				Vec3 look = Framing.lookVector(yaw, pitch);
				Vec3 right = Framing.rightVector(yaw);
				Vec3 up = right.cross(look);
				assertEquals(1, look.length(), 1e-9);
				assertEquals(1, right.length(), 1e-9);
				assertEquals(0, look.dot(right), 1e-9, "visee et droite doivent rester perpendiculaires");
				assertEquals(1, up.length(), 1e-9);
			}
		}
	}

	@Test
	@DisplayName("un cube tient dans le champ sous tous les angles")
	void cubeFitsFromEveryAngle() {
		AABB box = new AABB(-0.5, 0, -0.5, 0.5, 1, 0.5);
		Vec3 center = box.getCenter();
		for (double yaw : new double[]{-180, -90, -45, 0, 37, 90, 145}) {
			for (double pitch : new double[]{-80, -30, 0, 15, 80}) {
				assertFits(box, center, yaw, pitch, 60, 16.0 / 9.0, 1.0);
			}
		}
	}

	@Test
	@DisplayName("un sujet plat et large tient aussi, vu de face comme de cote")
	void flatSubjectFits() {
		// Une banniere : large en X, haute en Y, quasi nulle en Z.
		AABB box = new AABB(-2, 0, -0.05, 2, 3, 0.05);
		Vec3 center = box.getCenter();
		for (double yaw : new double[]{0, 90, 180, -90}) {
			assertFits(box, center, yaw, 0, 60, 16.0 / 9.0, 1.15);
		}
	}

	@Test
	@DisplayName("un sujet deux fois plus gros demande deux fois plus de recul")
	void distanceScalesWithSize() {
		Vec3 center = Vec3.ZERO;
		double small = Framing.fitDistance(new AABB(-1, -1, -1, 1, 1, 1), center, 0, 0, 60, 16.0 / 9.0, 1.0);
		double large = Framing.fitDistance(new AABB(-2, -2, -2, 2, 2, 2), center, 0, 0, 60, 16.0 / 9.0, 1.0);
		assertEquals(2 * small, large, 1e-9);
	}

	@Test
	@DisplayName("un champ plus etroit ou une marge plus large eloignent la camera")
	void narrowFieldAndMarginPushCameraBack() {
		AABB box = new AABB(-1, -1, -1, 1, 1, 1);
		Vec3 c = Vec3.ZERO;
		double wide = Framing.fitDistance(box, c, 0, 0, 90, 16.0 / 9.0, 1.0);
		double narrow = Framing.fitDistance(box, c, 0, 0, 30, 16.0 / 9.0, 1.0);
		assertTrue(narrow > wide, "un champ etroit demande plus de recul");
		double withMargin = Framing.fitDistance(box, c, 0, 0, 60, 16.0 / 9.0, 1.5);
		double without = Framing.fitDistance(box, c, 0, 0, 60, 16.0 / 9.0, 1.0);
		assertTrue(withMargin > without, "une marge laisse de l'air autour du sujet");
	}

	@Test
	@DisplayName("un ecran large contraint moins qu'un ecran carre")
	void wideScreenNeedsLessDistance() {
		AABB box = new AABB(-2, -0.5, -0.5, 2, 0.5, 0.5);
		Vec3 c = Vec3.ZERO;
		double wide = Framing.fitDistance(box, c, 0, 0, 60, 21.0 / 9.0, 1.0);
		double square = Framing.fitDistance(box, c, 0, 0, 60, 1.0, 1.0);
		assertTrue(wide < square, "un sujet large tient de plus pres sur un ecran large");
	}

	@Test
	@DisplayName("les angles sont ramenes dans [-180, 180[")
	void wrapDegreesStaysInRange() {
		assertEquals(0, Framing.wrapDegrees(360), EPS);
		assertEquals(-90, Framing.wrapDegrees(270), EPS);
		assertEquals(-180, Framing.wrapDegrees(180), EPS);
		assertEquals(179, Framing.wrapDegrees(-181), EPS);
		for (double d = -1000; d <= 1000; d += 7.3) {
			double w = Framing.wrapDegrees(d);
			assertTrue(w >= -180 && w < 180, "hors intervalle : " + w);
		}
	}
}
