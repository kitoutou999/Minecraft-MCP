package fr.tomda.mcbridge.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Le garde-fou doit voir la commande reelle, y compris derriere un execute ou un prefixe. */
class CommandGuardTest {

	private static final List<String> BLOCKED = List.of("stop", "restart", "reload");

	@Test
	@DisplayName("une commande ordinaire passe")
	void harmlessCommandPasses() {
		assertNull(CommandGuard.firstBlocked("list", BLOCKED));
		assertNull(CommandGuard.firstBlocked("mm mobs list", BLOCKED));
		assertNull(CommandGuard.firstBlocked("say le serveur ne stop pas", BLOCKED),
				"seule la tete de commande compte, pas les arguments");
	}

	@Test
	@DisplayName("la commande refusee est reconnue, quelle que soit la casse ou le prefixe")
	void blockedHeadIsCaught() {
		assertEquals("stop", CommandGuard.firstBlocked("stop", BLOCKED));
		assertEquals("stop", CommandGuard.firstBlocked("STOP", BLOCKED));
		assertEquals("stop", CommandGuard.firstBlocked("minecraft:stop", BLOCKED));
		assertEquals("reload", CommandGuard.firstBlocked("  reload confirm  ", BLOCKED));
	}

	@Test
	@DisplayName("une commande cachee derriere un execute est vue")
	void executeRunIsInspected() {
		assertEquals("stop", CommandGuard.firstBlocked("execute as @a run stop", BLOCKED));
		assertEquals("stop", CommandGuard.firstBlocked("execute at @p run minecraft:stop", BLOCKED));
		assertEquals("stop", CommandGuard.firstBlocked(
				"execute as @a at @s run execute if block ~ ~ ~ stone run stop", BLOCKED),
				"les execute imbriques sont suivis jusqu'au bout");
	}

	@Test
	@DisplayName("un execute inoffensif reste autorise")
	void harmlessExecuteStillPasses() {
		assertNull(CommandGuard.firstBlocked("execute as @a run say bonjour", BLOCKED));
		assertNull(CommandGuard.firstBlocked("execute positioned 0 64 0 run tp Steve ~ ~ ~", BLOCKED));
	}

	@Test
	@DisplayName("les tetes extraites sont le premier mot et ce qui suit chaque run")
	void headsAreFirstWordAndAfterRun() {
		assertEquals(List.of("execute", "say"), CommandGuard.heads("execute as @a run say salut"));
		assertEquals(List.of("list"), CommandGuard.heads("list"));
		assertEquals(List.of(), CommandGuard.heads("   "));
		assertEquals(List.of(), CommandGuard.heads(null));
	}

	@Test
	@DisplayName("une liste de refus vide laisse tout passer")
	void emptyBlockListAllowsEverything() {
		assertNull(CommandGuard.firstBlocked("stop", List.of()));
		assertNull(CommandGuard.firstBlocked("stop", null));
	}
}
