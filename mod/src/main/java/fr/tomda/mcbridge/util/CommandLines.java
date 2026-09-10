package fr.tomda.mcbridge.util;

import java.util.Locale;

/**
 * Construction des commandes que le mod envoie lui-meme.
 *
 * <p>Isole du reste pour etre verifiable : une commande mal formee ne leve rien, elle deplace le
 * joueur ailleurs que prevu, et la capture suivante montre autre chose sans que personne ne le
 * signale.
 *
 * <p>Deux pieges, tous deux constates en jeu :
 * <ul>
 *   <li><b>Le prefixe de namespace.</b> Un serveur avec EssentialsX reprend {@code tp} et
 *       {@code gamemode} avec sa propre syntaxe : {@code /tp @s x y z} repond « Joueur introuvable »
 *       et une teleportation Essentials passe par un delai d'attente. D'ou {@code minecraft:tp}.</li>
 *   <li><b>La dimension, cote console.</b> La console n'a pas de « soi » : une teleportation RCON
 *       s'execute depuis l'overworld, donc {@code tp joueur x y z} sort le joueur de son monde et
 *       le depose aux memes coordonnees dans l'overworld. Il faut {@code execute in <dimension>
 *       run tp ...}, sans quoi photographier une entite d'un monde personnalise ejecte le joueur.</li>
 * </ul>
 */
public final class CommandLines {
	private CommandLines() {}

	/** Coordonnees et orientation, arrondies court pour rester lisibles dans les journaux. */
	private static String args(double x, double y, double z, float yaw, float pitch) {
		return String.format(Locale.ROOT, "%.4f %.4f %.4f %.3f %.3f", x, y, z, yaw, pitch);
	}

	/**
	 * Teleportation en tant que joueur : le selecteur {@code @s} le designe lui-meme.
	 *
	 * @param teleportCommand forme qualifiee, {@code minecraft:tp} par defaut
	 * @param feetY           hauteur des pieds, une teleportation ne visant jamais les yeux
     */
	public static String teleportAsPlayer(String teleportCommand, double x, double feetY, double z,
	                                      float yaw, float pitch) {
		return teleportCommand + " @s " + args(x, feetY, z, yaw, pitch);
	}

	/**
	 * Teleportation en tant que console : le joueur est nomme, et la dimension imposee.
	 *
	 * @param dimension identifiant complet, par exemple {@code minecraft:overworld}
	 */
	public static String teleportAsConsole(String teleportCommand, String dimension, String playerName,
	                                       double x, double feetY, double z, float yaw, float pitch) {
		String target = playerName + " " + args(x, feetY, z, yaw, pitch);
		if (dimension == null || dimension.isBlank()) return teleportCommand + " " + target;
		return "minecraft:execute in " + dimension + " run " + teleportCommand + " " + target;
	}

	public static String gamemodeAsPlayer(String gamemodeCommand, String mode) {
		return gamemodeCommand + " " + mode;
	}

	/** La console doit nommer le joueur ; la dimension n'entre pas en jeu pour un mode. */
	public static String gamemodeAsConsole(String gamemodeCommand, String mode, String playerName) {
		return gamemodeCommand + " " + mode + " " + playerName;
	}
}
