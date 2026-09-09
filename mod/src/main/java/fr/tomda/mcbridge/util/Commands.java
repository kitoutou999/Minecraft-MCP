package fr.tomda.mcbridge.util;

import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.RpcException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Envoi de commandes au serveur, limite en debit, et deplacement de camera econome en commandes.
 *
 * <p>Un serveur Minecraft compte les commandes comme du spam : chaque envoi ajoute 20 a un compteur
 * qui ne diminue que d'une unite par tick, et depasser 200 deconnecte le joueur. Autrement dit, une
 * rafale de dix commandes suffit a se faire expulser, et le rythme tenable est de l'ordre d'une
 * commande par seconde. Les outils de ce mod en envoyaient bien plus vite : reglage de cadrage,
 * comparaison de references, changements de mode de jeu.
 *
 * <p>Deux reponses. D'abord {@link #send} espace les envois de {@code commandMinIntervalMs}.
 * Ensuite {@link #moveCamera} evite la commande quand c'est possible : en spectateur, le serveur
 * accepte la position que le client annonce, donc un deplacement direct suffit, et la commande ne
 * sert plus que de repli quand le serveur corrige la position.
 */
public final class Commands {
	private static final Object LOCK = new Object();
	private static long lastSentMs = 0;

	private Commands() {}

	/** Envoie une commande en tant que joueur, en respectant l'intervalle minimal. */
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

	/**
	 * Place la camera a une position et une orientation donnees.
	 *
	 * <p>En spectateur, tente d'abord un deplacement cote client, sans aucune commande : le serveur
	 * laisse les spectateurs se placer librement. La position obtenue est ensuite verifiee, et une
	 * commande de teleportation prend le relais si le serveur a corrige le joueur. Hors spectateur,
	 * passe directement par la commande, le serveur annulant tout deplacement client non valide.
	 *
	 * @param eyePosition position visee pour les yeux du joueur
	 * @return {@code true} si le deplacement s'est fait sans commande
	 */
	public static boolean moveCamera(Vec3 eyePosition, float yaw, float pitch) throws Exception {
		Minecraft mc = ClientMc.mc();
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
			fr.tomda.mcbridge.bridge.MainThread.await(TickWaiter.after(2), 3000);
			Vec3 actual = ClientMc.call(() -> ClientMc.player().getEyePosition());
			if (actual.distanceTo(eyePosition) <= 0.5) return true;
		}

		double eyeHeight = ClientMc.call(() -> (double) ClientMc.player().getEyeHeight());
		send(String.format(java.util.Locale.ROOT, "%s @s %.4f %.4f %.4f %.3f %.3f",
				McBridgeMod.config().teleportCommand,
				eyePosition.x, eyePosition.y - eyeHeight, eyePosition.z, yaw, pitch));
		return false;
	}
}
