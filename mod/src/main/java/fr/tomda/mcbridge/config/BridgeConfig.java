package fr.tomda.mcbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.tomda.mcbridge.McBridgeMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Configuration persistante du bridge, fichier {@code config/mcbridge.json} du client.
 *
 * <p>Le jeton est genere au premier lancement et reste dans le fichier (jamais dans les logs).
 * Le serveur MCP le lit soit via {@code MCBRIDGE_TOKEN}, soit directement dans ce fichier via
 * {@code MCBRIDGE_CONFIG}.
 */
public final class BridgeConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final String FILE_NAME = "mcbridge.json";

	// --- reseau ---------------------------------------------------------------------------------
	/** Adresse d'ecoute. Garder 127.0.0.1 : le bridge donne un controle total du client. */
	public String host = "127.0.0.1";
	public int port = 25580;
	/** Secret attendu dans l'en-tete {@code Authorization: Bearer <token>}. */
	public String token = "";
	public boolean requireAuth = true;
	/** Accepter des connexions non locales (a n'activer que derriere un proxy authentifie). */
	public boolean allowRemote = false;

	// --- delais ---------------------------------------------------------------------------------
	/** Temps max qu'un appel peut attendre le thread de jeu. */
	public int callTimeoutMs = 8000;
	/** Temps max d'un rechargement de ressources (F3+T peut etre long avec un gros pack). */
	public int reloadTimeoutMs = 90000;

	/**
	 * Commandes vanilla que le mod envoie lui-meme, sous leur forme qualifiee.
	 *
	 * <p>Le prefixe {@code minecraft:} est indispensable des qu'un plugin redefinit la commande.
	 * Sur un serveur avec EssentialsX, {@code /tp @s x y z} repond "Joueur introuvable" car le
	 * plugin attend un nom de joueur, et {@code /gamemode} suit ses propres regles. La forme
	 * qualifiee vise toujours l'implementation vanilla, avec ses selecteurs et sa syntaxe.
	 * A changer seulement si le serveur bloque les commandes qualifiees.
	 */
	public String teleportCommand = "minecraft:tp";
	public String gamemodeCommand = "minecraft:gamemode";

	/**
	 * Intervalle minimal entre deux commandes envoyees par le mod.
	 *
	 * <p>Un serveur compte chaque commande comme du spam : le compteur monte de 20 par envoi et ne
	 * retombe que d'une unite par tick, si bien qu'une dizaine de commandes coup sur coup
	 * deconnecte le joueur avec "Kicked for spamming". Une seconde entre deux envois reste sous ce
	 * seuil. A augmenter si le serveur est plus strict, a baisser sur un serveur local sans
	 * protection.
	 */
	public int commandMinIntervalMs = 1100;

	/**
	 * En spectateur, deplacer la camera cote client plutot que par une commande de teleportation.
	 *
	 * <p>Le serveur laisse les spectateurs se placer librement, ce qui evite la plupart des
	 * commandes envoyees par les outils de cadrage et de comparaison. La position obtenue est
	 * verifiee apres coup, et la commande sert de repli si le serveur corrige le joueur.
	 */
	public boolean preferClientTeleport = true;

	// --- portes de capacites --------------------------------------------------------------------
	public boolean enableVision = true;
	public boolean enableCommands = true;
	public boolean enableClientOptions = true;
	public boolean enableFocus = true;
	public boolean enableReflection = true;

	/**
	 * Autorise {@code gui.click} a envoyer un vrai clic au serveur.
	 *
	 * <p>Desactive par defaut : contrairement au reste du mod, qui n'observe que le client, un clic
	 * modifie l'etat du serveur. Dans un menu de plugin il navigue, mais il peut aussi acheter,
	 * vendre ou consommer selon ce que fait le menu, et dans un vrai inventaire il deplace des
	 * objets. A n'activer que sur un serveur de developpement.
	 */
	public boolean enableGuiClicks = false;

	/**
	 * Types de clic autorises quand {@link #enableGuiClicks} est vrai.
	 *
	 * <p>Par defaut, seuls le clic simple et le clic rapide (equivalent maj-clic), qui suffisent a
	 * naviguer dans un menu. Les autres deplacent ou jettent des objets : {@code clone} duplique en
	 * creatif, {@code throw} jette au sol, {@code swap} echange avec la barre d'action,
	 * {@code pickup_all} ramasse toute une pile, {@code quick_craft} sert au glisser-deposer.
	 */
	public List<String> allowedClickTypes = new ArrayList<>(List.of("pickup", "quick_move"));

	// --- screenshots ----------------------------------------------------------------------------
	public Screenshot screenshot = new Screenshot();

	public static final class Screenshot {
		/** Largeur max par defaut de l'image renvoyee (0 = taille native du framebuffer). */
		public int defaultMaxWidth = 1280;
		/** "png" ou "jpeg". Le JPEG coute beaucoup moins de tokens pour un modele de vision. */
		public String defaultFormat = "jpeg";
		/** Qualite JPEG entre 0 et 1. */
		public double jpegQuality = 0.85;
		/** Ticks attendus par defaut avant la capture (laisse le temps aux chunks et modeles). */
		public int defaultWaitTicks = 2;
	}

	// --- reflexion ------------------------------------------------------------------------------
	public Reflection reflection = new Reflection();

	public static final class Reflection {
		public List<String> allowedPackages = new ArrayList<>(List.of(
				"net.minecraft.*", "com.mojang.*", "net.fabricmc.*", "fr.tomda.*",
				"java.util.*", "java.lang.*", "org.joml.*"));
		public List<String> blockedPackages = new ArrayList<>(List.of(
				"java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.System",
				"java.lang.reflect.*", "java.lang.invoke.*", "java.nio.file.*", "java.io.*", "java.net.*"));
		public int maxObjectRefs = 1000;
	}

	public transient Path source;

	public static BridgeConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		BridgeConfig cfg;
		if (Files.exists(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file), BridgeConfig.class);
				if (cfg == null) cfg = new BridgeConfig();
			} catch (Exception e) {
				McBridgeMod.LOGGER.error("[mcbridge] config illisible, valeurs par defaut utilisees", e);
				cfg = new BridgeConfig();
			}
		} else {
			cfg = new BridgeConfig();
		}
		if (cfg.screenshot == null) cfg.screenshot = new Screenshot();
		if (cfg.teleportCommand == null || cfg.teleportCommand.isBlank()) cfg.teleportCommand = "minecraft:tp";
		if (cfg.gamemodeCommand == null || cfg.gamemodeCommand.isBlank()) cfg.gamemodeCommand = "minecraft:gamemode";
		if (cfg.commandMinIntervalMs < 0) cfg.commandMinIntervalMs = 1100;
		if (cfg.allowedClickTypes == null || cfg.allowedClickTypes.isEmpty()) {
			cfg.allowedClickTypes = new ArrayList<>(List.of("pickup", "quick_move"));
		}
		if (cfg.reflection == null) cfg.reflection = new Reflection();
		if (cfg.token == null || cfg.token.isBlank()) {
			cfg.token = UUID.randomUUID().toString().replace("-", "");
		}
		cfg.source = file;
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			if (source == null) source = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
			Files.createDirectories(source.getParent());
			Files.writeString(source, GSON.toJson(this));
		} catch (IOException e) {
			McBridgeMod.LOGGER.error("[mcbridge] impossible d'ecrire la config", e);
		}
	}
}
