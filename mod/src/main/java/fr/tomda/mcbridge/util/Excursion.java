package fr.tomda.mcbridge.util;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Sortie temporaire du client, et retour a l'etat de depart.
 *
 * <p>Photographier quelque chose demande toujours la meme sequence : relever ou se trouve le
 * joueur et dans quel mode il est, passer en spectateur pour ne pas tomber, forcer un champ de
 * vision reproductible, isoler le sujet, prendre la vue, puis tout remettre en place. Cette classe
 * porte la sequence une fois pour toutes ; les handlers de capture, de cadrage et de reference ne
 * decrivent plus que ce qu'ils veulent en garder.
 *
 * <p>S'utilise en try-with-resources : {@link #close()} restaure ce qui a ete change, dans l'ordre
 * inverse, sans jamais lever. Chaque etape est tentee meme si la precedente a echoue.
 *
 * <p><b>Regle de securite.</b> Rendre le mode de jeu sans rendre la position remettrait un joueur
 * en survie la ou la camera se trouve, souvent en l'air, et il tomberait. {@code restoreMode} sans
 * {@code restorePosition} n'a donc de sens que si l'appelant garantit lui-meme le retour au sol,
 * comme {@code refs.compareAll} dont chaque comparaison restaure deja sa position.
 */
public final class Excursion implements AutoCloseable {

	/** Ce que l'excursion doit prendre en charge, et ce qu'elle doit rendre a la fin. */
	public static final class Options {
		private boolean spectator = false;
		private Integer fov = null;
		private boolean restorePosition = false;
		private boolean restoreMode = false;
		private boolean restoreFocus = false;

		/** Passer en spectateur : pas de chute, pas de collision, pas de modele de joueur a l'image. */
		public Options spectator(boolean value) {
			this.spectator = value;
			return this;
		}

		/** Champ de vision impose pendant l'excursion ; null laisse celui du joueur. */
		public Options fov(Integer value) {
			this.fov = value;
			return this;
		}

		/** Ramener le joueur a sa position et son orientation de depart. */
		public Options restorePosition(boolean value) {
			this.restorePosition = value;
			return this;
		}

		/** Rendre le mode de jeu de depart. Voir la regle de securite dans la doc de la classe. */
		public Options restoreMode(boolean value) {
			this.restoreMode = value;
			return this;
		}

		/** Sauvegarder l'etat du focus au debut et le rendre a la fin. */
		public Options restoreFocus(boolean value) {
			this.restoreFocus = value;
			return this;
		}
	}

	public static Options options() {
		return new Options();
	}

	private final Options opts;
	private final Vec3 startPos;
	private final float startYaw;
	private final float startPitch;
	private final String startMode;
	private final int startFov;
	private final FocusState.Snapshot focusBefore;
	private final Spectator.Guard guard;
	private final boolean fovChanged;
	private boolean closed = false;

	private Excursion(Options opts, Vec3 startPos, float startYaw, float startPitch, String startMode, int startFov,
	                  FocusState.Snapshot focusBefore, Spectator.Guard guard, boolean fovChanged) {
		this.opts = opts;
		this.startPos = startPos;
		this.startYaw = startYaw;
		this.startPitch = startPitch;
		this.startMode = startMode;
		this.startFov = startFov;
		this.focusBefore = focusBefore;
		this.guard = guard;
		this.fovChanged = fovChanged;
	}

	/**
	 * Releve l'etat de depart, passe en spectateur et applique le champ de vision demandes.
	 *
	 * @throws RpcException si le serveur refuse le passage en spectateur : mieux vaut ne rien faire
	 *                      que deplacer un joueur qui tomberait
	 */
	public static Excursion begin(Options opts) throws Exception {
		Minecraft mc = ClientMc.mc();
		Object[] before = ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			return new Object[]{p.position(), p.getYRot(), p.getXRot(),
					mc.gameMode != null ? mc.gameMode.getPlayerMode().getSerializedName() : "unknown",
					mc.options.fov().get()};
		});
		Vec3 pos = (Vec3) before[0];
		float yaw = (float) before[1];
		float pitch = (float) before[2];
		String mode = (String) before[3];
		int fov = (int) before[4];

		FocusState.Snapshot focus = opts.restoreFocus ? FocusState.INSTANCE.snapshot() : null;
		Spectator.Guard guard = opts.spectator ? Spectator.enter() : null;

		boolean fovChanged = false;
		if (opts.fov != null && opts.fov != fov) {
			int wanted = opts.fov;
			ClientMc.call(() -> {
				mc.options.fov().set(wanted);
				return null;
			});
			fovChanged = true;
		}
		return new Excursion(opts, pos, yaw, pitch, mode, fov, focus, guard, fovChanged);
	}

	/** Position des pieds au depart. */
	public Vec3 startPos() {
		return startPos;
	}

	/** Position des yeux au depart, celle que vise un placement de camera. */
	public Vec3 startEye() throws RpcException {
		return startPos.add(0, eyeHeight(), 0);
	}

	public float startYaw() {
		return startYaw;
	}

	public float startPitch() {
		return startPitch;
	}

	public String startMode() {
		return startMode;
	}

	public int startFov() {
		return startFov;
	}

	/**
	 * Hauteur des yeux courante.
	 *
	 * <p>Lue a la demande et non au depart : la posture change avec le mode de jeu, et une
	 * teleportation place les pieds alors que la camera vise les yeux.
	 */
	public double eyeHeight() throws RpcException {
		return ClientMc.call(() -> (double) ClientMc.player().getEyeHeight());
	}

	/** Vrai si le mode de jeu a reellement ete change pour cette excursion. */
	public boolean spectatorApplied() {
		return guard != null && guard.changed();
	}

	/** Vrai si le joueur est en spectateur, qu'on l'y ait mis ou qu'il y etait deja. */
	public boolean inSpectator() {
		return guard != null || "spectator".equals(startMode);
	}

	/** Resume pour les metadonnees d'une reponse. */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("requested", opts.spectator);
		o.addProperty("applied", inSpectator());
		o.addProperty("switched", spectatorApplied());
		o.addProperty("previousMode", startMode);
		return o;
	}

	/**
	 * Rend ce qui a ete pris : focus, champ de vision, position, mode de jeu. Ne leve jamais, et
	 * tente chaque etape independamment pour qu'un echec n'en annule pas d'autres.
	 */
	@Override
	public void close() {
		if (closed) return;
		closed = true;

		if (focusBefore != null) {
			try {
				FocusState.INSTANCE.restore(focusBefore);
			} catch (Exception e) {
				McBridgeMod.LOGGER.warn("[mcbridge] restauration du focus impossible", e);
			}
		}
		if (fovChanged) {
			try {
				ClientMc.call(() -> {
					ClientMc.mc().options.fov().set(startFov);
					return null;
				});
			} catch (Exception e) {
				McBridgeMod.LOGGER.warn("[mcbridge] restauration du champ de vision impossible", e);
			}
		}
		boolean positionRestored = false;
		if (opts.restorePosition) {
			try {
				Commands.moveCamera(startEye(), startYaw, startPitch);
				positionRestored = true;
			} catch (Exception e) {
				McBridgeMod.LOGGER.warn("[mcbridge] retour a la position de depart impossible", e);
			}
		}
		// Le mode n'est rendu que si le joueur est bien revenu, sauf demande explicite de l'appelant
		// qui garantit alors lui-meme le retour au sol.
		if (opts.restoreMode && (positionRestored || !opts.restorePosition)) {
			Spectator.restore(guard);
		} else if (opts.restoreMode) {
			McBridgeMod.LOGGER.warn("[mcbridge] mode de jeu '{}' non rendu : le joueur n'a pas pu revenir a sa position, "
					+ "le rendre ici le ferait tomber", startMode);
		}
	}
}
