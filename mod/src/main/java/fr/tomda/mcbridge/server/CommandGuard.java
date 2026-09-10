package fr.tomda.mcbridge.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Garde-fou sur les commandes envoyees a la console du serveur.
 *
 * <p>Comparer le premier mot d'une commande a une liste de refus ne suffit pas : {@code execute run
 * stop} arrete le serveur sans jamais commencer par {@code stop}, et {@code minecraft:stop} designe
 * la meme commande sous un autre nom. On extrait donc toutes les tetes de commande d'une ligne,
 * c'est-a-dire le premier mot et tout mot qui suit un {@code run}, chacune debarrassee de son
 * prefixe de plugin.
 *
 * <p>Il s'agit d'eviter l'accident, pas de resister a quelqu'un qui cherche a contourner : qui
 * dispose du mot de passe RCON a deja tous les droits sur le serveur.
 */
public final class CommandGuard {
	private CommandGuard() {}

	/** Tetes de commande d'une ligne : le premier mot, et tout mot qui suit un {@code run}. */
	public static List<String> heads(String command) {
		List<String> heads = new ArrayList<>();
		if (command == null) return heads;
		String[] words = command.trim().split("\\s+");
		boolean expectHead = true;
		for (String word : words) {
			if (word.isEmpty()) continue;
			if (expectHead) {
				heads.add(normalize(word));
				expectHead = false;
			}
			// 'execute ... run <commande>' : ce qui suit run est une commande a part entiere.
			if ("run".equals(normalize(word))) expectHead = true;
		}
		return heads;
	}

	/** Nom de la premiere tete refusee, ou null si la commande passe. */
	public static String firstBlocked(String command, List<String> blocked) {
		if (blocked == null || blocked.isEmpty()) return null;
		List<String> normalizedBlocked = new ArrayList<>();
		for (String b : blocked) {
			if (b != null && !b.isBlank()) normalizedBlocked.add(normalize(b));
		}
		for (String head : heads(command)) {
			if (normalizedBlocked.contains(head)) return head;
		}
		return null;
	}

	/** Minuscules, sans le prefixe de plugin : {@code minecraft:stop} devient {@code stop}. */
	private static String normalize(String word) {
		String w = word.toLowerCase(Locale.ROOT);
		int colon = w.indexOf(':');
		return colon >= 0 ? w.substring(colon + 1) : w;
	}
}
