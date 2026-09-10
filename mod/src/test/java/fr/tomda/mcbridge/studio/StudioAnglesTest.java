package fr.tomda.mcbridge.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioAnglesTest {

	@Test
	@DisplayName("chaque nom annonce dans le message d'erreur existe vraiment")
	void everyAdvertisedPresetResolves() {
		for (String name : StudioAngles.presetNames()) {
			assertNotNull(StudioAngles.preset(name), "preset annonce mais absent : " + name);
		}
	}

	@Test
	@DisplayName("les alias francais et la casse sont acceptes")
	void aliasesAndCaseAreAccepted() {
		assertEquals("front", StudioAngles.preset("FACE").name());
		assertEquals("back", StudioAngles.preset("Dos").name());
		assertEquals("right", StudioAngles.preset("droite").name());
		assertEquals("three_quarter", StudioAngles.preset("34").name());
		assertNull(StudioAngles.preset("inconnu"));
	}

	@Test
	@DisplayName("le tourne-disque repartit les vues sur un tour complet, sans doublon de nom")
	void turntableSpreadsEvenly() {
		List<StudioAngles> angles = StudioAngles.turntable(8, 15);
		assertEquals(8, angles.size());
		Set<String> names = new HashSet<>();
		for (int i = 0; i < angles.size(); i++) {
			StudioAngles a = angles.get(i);
			assertEquals(15, a.pitch(), 1e-9);
			assertEquals(360.0 * i / 8, a.azimuth(), 1e-9);
			assertTrue(names.add(a.name()), "nom de vue en double : " + a.name());
		}
	}
}
