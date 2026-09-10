package fr.tomda.mcbridge.util;

import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcException;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;


/**
 * Envoi de commandes au serveur, par le canal le moins couteux disponible.
 *
 * <p>Trois canaux, du meilleur au pire :
 * <ol>
 *   <li><b>Rien du tout</b> : en spectateur, le serveur accepte la position annoncee par le client,
 *       donc deplacer la camera ne demande aucune commande.</li>
 *   <li><b>RCON</b>, s'il est configure : la commande s'execute en tant que console, sans compteur
 *       anti-spam, sans exiger que le compte soit operateur, et sa sortie revient. La console n'a
 *       pas de « soi », donc chaque commande doit nommer le joueur au lieu d'utiliser {@code @s}.</li>
 *   <li><b>Commande joueur</b> : le repli historique. Un serveur ajoute 20 a un compteur par envoi,
 *       lequel ne retombe que d'une unite par tick et deconnecte a 200 : une rafale de dix
 *       commandes suffit. D'ou l'intervalle minimal impose entre deux envois.</li>
 * </ol>
 */
public final class Commands {
	private static final Object LOCK = new Object();
	private static long lastSentMs = 0;

	private Commands() {}

	/** Le canal effectivement utilise pour une action, renvoye dans les reponses. */
	public enum Channel {
		/** Deplacement applique cote client, sans rien envoyer. */
		CLIENT,
		/** Commande executee en tant que console. */
		RCON,
		/** Commande envoyee en tant que joueur. */
		PLAYER
	}

	/** Nom de compte du joueur, seule facon de le designer depuis la console. */
	public static String playerName() throws RpcException {
		return ClientMc.call(() -> ClientMc.player().getGameProfile().name());
	}

	/**
	 * Dimension courante du joueur, indispensable a toute commande passee par la console.
	 *
	 * <p>La console execute depuis l'overworld : sans cette precision, une teleportation RCON sort
	 * le joueur de son monde et le depose aux memes coordonnees ailleurs. Constate en jeu sur un
	 * monde personnalise.
	 */
	public static String dimension() throws RpcException {
		return ClientMc.call(() -> ClientMc.player().level().dimension().identifier().toString());
	}

	// --- envoi brut ---------------------------------------------------------------------------

    /**
     * Envoie une commande en tant que joueur, en respectant l'intervalle minimal.
     *
     * <p>Reservee a ce qui doit venir du joueur, comme {@code chat.send}. Pour une action qui
     * releve du serveur, preferer {@link #teleport} ou {@link #gamemode}, qui savent passer par
     * RCON.
     */
	public static void send(String command) throws RpcException {
		long wait;
		synchronized (LOCK) {
			long now = System.currentTimeMillis();
			long earliest = lastSentMs + Math.max(0, McBridgeMod.config().commandMinIntervalMs);
			wait = Math.max(0, earliest - now);
			lastSentMs = Math.max(now, earliest);
		}
		if (wait > 0) {
			// Attente sur le thread HTTP : ne jamais endormir le thread de rendu.
			try {
				Thread.sleep(wait);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RpcException("interrupted", "Interrompu en attendant de pouvoir envoyer une commande.");
			}
		}
		ClientMc.call(() -> {
			ClientMc.player().connection.sendCommand(command);
			return null;
		});
	}

	// --- actions qui relevent du serveur --------------------------------------------------------

	/**
	 * Teleporte le joueur. {@code eyePosition} vise les yeux ; la commande, elle, place les pieds.
	 */
	public static Channel teleport(Vec3 eyePosition, float yaw, float pitch) throws RpcException {
		double eyeHeight = ClientMc.call(() -> (double) ClientMc.player().getEyeHeight());
		String cmd = McBridgeMod.config().teleportCommand;
		double feetY = eyePosition.y - eyeHeight;
		if (Rcon.available()) {
			String line = CommandLines.teleportAsConsole(cmd, dimension(), playerName(),
					eyePosition.x, feetY, eyePosition.z, yaw, pitch);
			if (Rcon.tryRun(line) != null) return Channel.RCON;
		}
		send(CommandLines.teleportAsPlayer(cmd, eyePosition.x, feetY, eyePosition.z, yaw, pitch));
		return Channel.PLAYER;
	}

	/** Change le mode de jeu du joueur. */
	public static Channel gamemode(String mode) throws RpcException {
		String cmd = McBridgeMod.config().gamemodeCommand;
		if (Rcon.available() && Rcon.tryRun(CommandLines.gamemodeAsConsole(cmd, mode, playerName())) != null) {
			return Channel.RCON;
		}
		send(CommandLines.gamemodeAsPlayer(cmd, mode));
		return Channel.PLAYER;
	}

	// --- deplacement de camera ------------------------------------------------------------------

	/**
	 * Place la camera a une position et une orientation donnees, au meilleur canal disponible.
	 *
	 * <p>En spectateur, tente d'abord un deplacement cote client, sans aucune commande : le serveur
	 * laisse les spectateurs se placer librement. La position obtenue est verifiee, et une
	 * teleportation prend le relais si le serveur a corrige le joueur.
	 */
	public static Channel moveCamera(Vec3 eyePosition, float yaw, float pitch) throws Exception {
		boolean spectator = ClientMc.call(() -> ClientMc.player().isSpectator());
		if (spectator && McBridgeMod.config().preferClientTeleport) {
			double eyeHeight = ClientMc.call(() -> (double) ClientMc.player().getEyeHeight());
			ClientMc.call(() -> {
				LocalPlayer p = ClientMc.player();
				p.setPos(eyePosition.x, eyePosition.y - eyeHeight, eyePosition.z);
				p.setDeltaMovement(Vec3.ZERO);
				p.setYRot(yaw);
				p.setXRot(pitch);
				p.setYHeadRot(yaw);
				p.setYBodyRot(yaw);
				return null;
			});
			// Laisser au serveur le temps d'accepter ou de corriger avant de conclure.
			MainThread.await(TickWaiter.after(2), 3000);
			Vec3 actual = ClientMc.call(() -> ClientMc.player().getEyePosition());
			if (actual.distanceTo(eyePosition) <= 0.5) return Channel.CLIENT;
		}
		return teleport(eyePosition, yaw, pitch);
	}
}
