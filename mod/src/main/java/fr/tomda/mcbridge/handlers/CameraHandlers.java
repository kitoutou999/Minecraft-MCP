package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.util.ClientMc;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Orientation de la vue du joueur local (la camera en premiere personne suit le joueur).
 *
 * <p>Convention Minecraft : yaw 0 = sud, 90 = ouest, -90 = est, 180 = nord ;
 * pitch -90 = vers le haut, 90 = vers le bas.
 * Le deplacement (teleportation) passe par une commande {@code /tp} envoyee en tant que joueur,
 * voir {@code chat.send} et le parametre {@code teleport} de {@code vision.screenshot}.
 */
public final class CameraHandlers {
	private CameraHandlers() {}

	public static void register(RpcRouter router) {
		router.register("camera.get", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			Camera cam = ClientMc.mc().gameRenderer.getMainCamera();
			JsonObject o = new JsonObject();
			o.add("cameraPos", Json.vec(cam.position()));
			o.addProperty("cameraYaw", cam.yRot());
			o.addProperty("cameraPitch", cam.xRot());
			o.addProperty("detached", cam.isDetached());
			o.add("playerPos", Json.vec(p.position()));
			o.addProperty("playerYaw", p.getYRot());
			o.addProperty("playerPitch", p.getXRot());
			return o;
		}));

		router.register("camera.look", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			float yaw = p.getYRot();
			float pitch = p.getXRot();
			if (ctx.has("yaw")) yaw = (float) ctx.getDouble("yaw");
			if (ctx.has("pitch")) pitch = (float) ctx.getDouble("pitch");
			if (ctx.has("deltaYaw")) yaw += (float) ctx.getDouble("deltaYaw");
			if (ctx.has("deltaPitch")) pitch += (float) ctx.getDouble("deltaPitch");
			applyLook(p, yaw, Mth.clamp(pitch, -90f, 90f));
			return look(p);
		}));

		router.register("camera.lookAt", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			double dx = ctx.getDouble("x") - p.getX();
			double dy = ctx.getDouble("y") - p.getEyeY();
			double dz = ctx.getDouble("z") - p.getZ();
			double horiz = Math.sqrt(dx * dx + dz * dz);
			float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
			float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
			applyLook(p, yaw, Mth.clamp(pitch, -90f, 90f));
			return look(p);
		}));
	}

	/** Applique yaw/pitch au joueur, tete et corps compris (sinon le corps traine derriere). */
	public static void applyLook(LocalPlayer p, float yaw, float pitch) {
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private static JsonObject look(LocalPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("yaw", p.getYRot());
		o.addProperty("pitch", p.getXRot());
		return o;
	}
}
