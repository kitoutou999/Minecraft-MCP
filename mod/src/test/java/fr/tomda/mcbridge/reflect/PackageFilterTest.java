package fr.tomda.mcbridge.reflect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Liste d'autorisation de la reflexion : c'est elle qui tient la porte du client. */
class PackageFilterTest {

	private static final PackageFilter DEFAULT_FILTER = new PackageFilter(
			List.of("net.minecraft.*", "com.mojang.*", "java.util.*", "java.lang.*"),
			List.of("java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.System",
					"java.lang.reflect.*", "java.nio.file.*", "java.io.*"));

	@Test
	@DisplayName("un package autorise passe, un package inconnu non")
	void allowedPackagesPass() {
		assertTrue(DEFAULT_FILTER.isAllowed("net.minecraft.client.Minecraft"));
		assertTrue(DEFAULT_FILTER.isAllowed("com.mojang.authlib.GameProfile"));
		assertFalse(DEFAULT_FILTER.isAllowed("me.cortex.voxy.client.core.VoxyRenderSystem"));
		assertFalse(DEFAULT_FILTER.isAllowed("org.spongepowered.asm.mixin.Mixins"));
	}

	@Test
	@DisplayName("le blocage l'emporte sur l'autorisation")
	void blockWinsOverAllow() {
		assertTrue(DEFAULT_FILTER.isAllowed("java.lang.String"));
		assertFalse(DEFAULT_FILTER.isAllowed("java.lang.Runtime"), "Runtime executerait des processus");
		assertFalse(DEFAULT_FILTER.isAllowed("java.lang.ProcessBuilder"));
		assertFalse(DEFAULT_FILTER.isAllowed("java.lang.reflect.Method"), "contourner le filtre par la reflexion");
		assertFalse(DEFAULT_FILTER.isAllowed("java.io.File"));
	}

	@Test
	@DisplayName("un motif de package ne deborde pas sur un package voisin")
	void packagePrefixDoesNotLeak() {
		PackageFilter f = new PackageFilter(List.of("net.minecraft.*"), List.of());
		assertTrue(f.isAllowed("net.minecraft.world.entity.Entity"));
		assertTrue(f.isAllowed("net.minecraft"), "le package lui-meme est couvert");
		assertFalse(f.isAllowed("net.minecraftforge.common.Config"),
				"un prefixe de chaine ne doit pas suffire, il faut la frontiere de package");
	}

	@Test
	@DisplayName("une classe exacte autorise seulement elle-meme")
	void exactClassPatternIsExact() {
		PackageFilter f = new PackageFilter(List.of("java.lang.String"), List.of());
		assertTrue(f.isAllowed("java.lang.String"));
		assertFalse(f.isAllowed("java.lang.StringBuilder"));
	}

	@Test
	@DisplayName("sans liste d'autorisation, rien ne passe")
	void emptyAllowListRefusesEverything() {
		PackageFilter f = new PackageFilter(List.of(), List.of());
		assertFalse(f.isAllowed("net.minecraft.client.Minecraft"));
		assertFalse(new PackageFilter(null, null).isAllowed("java.lang.String"));
	}
}
