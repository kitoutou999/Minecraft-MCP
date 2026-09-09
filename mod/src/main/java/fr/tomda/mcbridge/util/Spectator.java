package fr.tomda.mcbridge.util;

import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcException;
import net.minecraft.client.Minecraft;

/**
 * Passage temporaire en spectateur, pour placer la camera sans conséquence.
 *
 * <p>Teleporter un joueur en survie a dix blocs du sol le fait tomber aussitot, avec les degats
 * qui vont avec. Le spectateur regle le probleme d'un coup : pas de gravite, pas de collision,
 * pas de modele de joueur dans l'image, et le serveur accepte la position annoncee par le client,
 * ce qui evite meme la commande de teleportation.
 *
 * <p>Le vol en creatif serait une alternative, mais le joueur resterait visible des autres et
 * garderait ses collisions, donc une camera placee dans un mur ne fonctionnerait pas.
 */
public final class Spectator {
	private Spectator() {}

	/** Ce qu'il faudra remettre en place. {@code changed} est faux si le joueur y etait deja. */
	public record Guard(String previousMode, boolean changed) {}

	public static String currentMode() throws RpcException {
		return ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			return mc.gameMode != null ? mc.gameMode.getPlayerMode().getSerializedName() : "unknown";
		});
	}

	/**
	 * Passe en spectateur si besoin, et verifie que le serveur a suivi.
	 *
	 * @throws RpcException si le serveur refuse, plutot que de deplacer un joueur qui tomberait
	 */
	public static Guard enter() throws Exception {
		String mode = currentMode();
		if ("spectator".equals(mode)) return new Guard(mode, false);
		Commands.send(McBridgeMod.config().gamemodeCommand + " spectator");
		MainThread.await(TickWaiter.after(4), 4000);
		String now = currentMode();
		if (!"spectator".equals(now)) {
			throw RpcException.forbidden("Passage en spectateur refuse par le serveur (mode actuel : " + now
					+ "). Sans lui, un joueur teleporte en l'air tombe. Donner la permission au joueur, se mettre en "
					+ "spectateur a la main, ou accepter la chute avec stabilize:false.");
		}
		return new Guard(mode, true);
	}

	/** Remet le mode de jeu d'origine, sans jamais faire echouer l'appel en cours. */
	public static void restore(Guard guard) {
		if (guard == null || !guard.changed() || "unknown".equals(guard.previousMode())) return;
		try {
			Commands.send(McBridgeMod.config().gamemodeCommand + " " + guard.previousMode());
		} catch (Exception e) {
			McBridgeMod.LOGGER.warn("[mcbridge] retour au mode de jeu '{}' impossible", guard.previousMode(), e);
		}
	}
}
