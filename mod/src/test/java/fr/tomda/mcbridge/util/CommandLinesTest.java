package fr.tomda.mcbridge.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commandes envoyees par le mod. Les deux pieges testes ici ont ete constates en jeu : une commande
 * reprise par un plugin, et une teleportation console qui sort le joueur de son monde.
 */
class CommandLinesTest {

	@Test
	@DisplayName("en tant que joueur, le selecteur @s designe le joueur lui-meme")
	void playerTeleportUsesSelector() {
		String line = CommandLines.teleportAsPlayer("minecraft:tp", 74.876322824, 86.0, 71.409710846, -0.164f, -3.6f);
		assertEquals("minecraft:tp @s 74.8763 86.0000 71.4097 -0.164 -3.600", line);
	}

	@Test
	@DisplayName("en tant que console, le joueur est nomme et la dimension imposee")
	void consoleTeleportKeepsTheDimension() {
		String line = CommandLines.teleportAsConsole("minecraft:tp", "minecraft:clicker_spawn", "kitoutou999",
				74.876322824, 86.0, 71.409710846, -0.164f, -3.6f);
		assertEquals("minecraft:execute in minecraft:clicker_spawn run minecraft:tp kitoutou999 "
				+ "74.8763 86.0000 71.4097 -0.164 -3.600", line);
	}

	@Test
	@DisplayName("sans la dimension, la console deposerait le joueur dans l'overworld")
	void consoleTeleportWithoutDimensionIsTheBugToAvoid() {
		// Constate en jeu : 'tp joueur x y z' lance depuis la console sort le joueur d'un monde
		// personnalise. La commande sans 'execute in' n'est donc produite que si la dimension est
		// inconnue, faute de mieux.
		String withDimension = CommandLines.teleportAsConsole("minecraft:tp", "minecraft:clicker_spawn",
				"kitoutou999", 0, 64, 0, 0, 0);
		assertTrue(withDimension.startsWith("minecraft:execute in minecraft:clicker_spawn run "));
		String withoutDimension = CommandLines.teleportAsConsole("minecraft:tp", null, "kitoutou999", 0, 64, 0, 0, 0);
		assertEquals("minecraft:tp kitoutou999 0.0000 64.0000 0.0000 0.000 0.000", withoutDimension);
		assertEquals(withoutDimension, CommandLines.teleportAsConsole("minecraft:tp", "  ", "kitoutou999", 0, 64, 0, 0, 0));
	}

	@Test
	@DisplayName("la commande garde son prefixe de namespace, sinon un plugin la reprend")
	void namespacePrefixIsPreserved() {
		// Sur un serveur avec EssentialsX, 'tp' sans prefixe repond « Teleportation en cours » et
		// n'envoie pas le joueur ou on croit.
		for (String line : new String[]{
				CommandLines.teleportAsPlayer("minecraft:tp", 0, 64, 0, 0, 0),
				CommandLines.teleportAsConsole("minecraft:tp", "minecraft:overworld", "j", 0, 64, 0, 0, 0),
				CommandLines.gamemodeAsPlayer("minecraft:gamemode", "spectator"),
				CommandLines.gamemodeAsConsole("minecraft:gamemode", "spectator", "j")}) {
			assertTrue(line.contains("minecraft:"), "prefixe perdu : " + line);
		}
	}

	@Test
	@DisplayName("la teleportation vise les pieds, jamais les yeux")
	void teleportTargetsFeet() {
		// La camera est a hauteur des yeux, 1,62 bloc plus haut : c'est a l'appelant de soustraire,
		// et la commande ne recoit que la hauteur des pieds.
		String line = CommandLines.teleportAsPlayer("minecraft:tp", 10, 86.0, 20, 0, 0);
		assertTrue(line.contains(" 86.0000 "), "hauteur des pieds attendue dans " + line);
	}

	@Test
	@DisplayName("le mode de jeu se nomme differemment selon le canal")
	void gamemodeDependsOnTheChannel() {
		assertEquals("minecraft:gamemode spectator", CommandLines.gamemodeAsPlayer("minecraft:gamemode", "spectator"));
		assertEquals("minecraft:gamemode survival kitoutou999",
				CommandLines.gamemodeAsConsole("minecraft:gamemode", "survival", "kitoutou999"));
	}

	@Test
	@DisplayName("les nombres sont ecrits en point decimal, quelle que soit la langue du systeme")
	void numbersUseDotSeparator() {
		String line = CommandLines.teleportAsPlayer("minecraft:tp", 1.5, 2.25, 3.75, 45.5f, -12.25f);
		assertEquals("minecraft:tp @s 1.5000 2.2500 3.7500 45.500 -12.250", line);
	}
}
