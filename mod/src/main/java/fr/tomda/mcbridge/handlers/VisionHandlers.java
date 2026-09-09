package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcContext;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.util.CaptureState;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.Commands;
import fr.tomda.mcbridge.util.EntityJson;
import fr.tomda.mcbridge.util.Images;
import fr.tomda.mcbridge.util.Spectator;
import fr.tomda.mcbridge.util.TickWaiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Vision : capture du framebuffer et description structuree de la scene.
 *
 * <p>Sequence d'une capture :
 * <ol>
 *   <li>thread de rendu : masquage du HUD, et de l'ecran ouvert s'il y en a un (il reste ouvert,
 *       voir {@code ScreenMixin})</li>
 *   <li>attente de {@code waitTicks} ticks, pour que la frame reflete ces changements et que les
 *       chunks et modeles aient eu le temps de charger</li>
 *   <li>thread de rendu : {@code Screenshot.takeScreenshot} (lecture GPU asynchrone), puis
 *       restauration du HUD dans le callback</li>
 *   <li>thread HTTP : decoupage, redimensionnement et encodage PNG ou JPEG</li>
 * </ol>
 * Note 26.2 : {@code Minecraft.getMainRenderTarget()} devient {@code gameRenderer.mainRenderTarget()}.
 */
public final class VisionHandlers {
	private VisionHandlers() {}

	public static void register(RpcRouter router) {
		router.register("vision.screenshot", VisionHandlers::screenshot);

		router.register("vision.burst", VisionHandlers::burst);

		router.register("vision.describeScene", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			Minecraft mc = ClientMc.mc();
			double radius = ctx.optDouble("radius", 32.0);
			int max = ctx.optInt("maxEntities", 50);

			JsonObject o = new JsonObject();
			o.add("eye", Json.vec(p.getEyePosition()));
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());

			JsonObject la = new JsonObject();
			HitResult hit = mc.hitResult;
			if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos bp = bhr.getBlockPos();
				la.addProperty("type", "block");
				la.addProperty("id", BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString());
				la.add("pos", Json.blockPos(bp));
				la.addProperty("face", bhr.getDirection().getName());
			} else if (hit instanceof EntityHitResult ehr) {
				la.addProperty("type", "entity");
				la.add("entity", EntityJson.summary(ehr.getEntity(), p.position()));
			} else {
				la.addProperty("type", "none");
			}
			o.add("lookingAt", la);

			Vec3 from = p.position();
			List<Entity> near = new ArrayList<>();
			for (Entity e : level.entitiesForRendering()) {
				if (e == p) continue;
				if (e.distanceToSqr(from) <= radius * radius) near.add(e);
			}
			near.sort((a, b) -> Double.compare(a.distanceToSqr(from), b.distanceToSqr(from)));
			JsonArray arr = new JsonArray();
			for (int i = 0; i < near.size() && i < max; i++) arr.add(EntityJson.summary(near.get(i), from));
			o.add("entities", arr);
			o.addProperty("entitiesTotal", near.size());
			return o;
		}));
	}

	// --- tool vision.burst -------------------------------------------------------------------------

	/**
	 * Serie de captures espacees dans le temps, pour juger un mouvement plutot qu'une pose.
	 *
	 * <p>Une seule image ne dit rien d'une animation ModelEngine, d'un effet de particules, d'un
	 * sort MythicMobs ou d'une transition de HUD. Par defaut les images sont assemblees en une
	 * planche unique, lue de gauche a droite puis de haut en bas : le mouvement se voit d'un coup
	 * pour le cout d'une seule image. Le mode image par image reste disponible quand le detail
	 * compte plus que le nombre de vues.
	 */
	private static JsonObject burst(RpcContext ctx) throws Exception {
		BridgeConfig cfg = McBridgeMod.config();
		if (!cfg.enableVision) throw RpcException.unavailable("La vision est desactivee (enableVision=false).");
		Minecraft mc = ClientMc.mc();
		if (mc.player == null || mc.level == null) throw RpcException.noPlayer();

		int frames = Math.max(2, Math.min(ctx.optInt("frames", 6), 24));
		int interval = Math.max(1, Math.min(ctx.optInt("intervalTicks", 4), 40));
		boolean hideHud = ctx.optBoolean("hideHud", true);
		boolean hideScreen = ctx.optBoolean("hideScreen", true);
		String layout = ctx.optString("layout", "sheet").toLowerCase(Locale.ROOT);
		String format = ctx.optString("format", "jpeg").toLowerCase(Locale.ROOT);
		double quality = ctx.optDouble("quality", cfg.screenshot.jpegQuality);
		int cellWidth = ctx.optInt("cellWidth", 320);
		int columns = ctx.optInt("columns", 0);
		int maxWidth = ctx.optInt("maxWidth", cfg.screenshot.defaultMaxWidth);

		List<byte[]> shots = new ArrayList<>();
		List<Long> ticks = new ArrayList<>();
		for (int i = 0; i < frames; i++) {
			// La premiere capture attend un tick, les suivantes l'intervalle demande.
			shots.add(captureRawPng(cfg, mc, hideHud, hideScreen, i == 0 ? 1 : interval));
			ticks.add(TickWaiter.currentTick());
		}

		JsonObject o;
		if (layout.equals("frames")) {
			JsonArray arr = new JsonArray();
			for (int i = 0; i < shots.size(); i++) {
				JsonObject img = Images.encode(shots.get(i), maxWidth, format, quality);
				img.addProperty("frame", i);
				img.addProperty("tick", ticks.get(i));
				arr.add(img);
			}
			o = new JsonObject();
			o.add("shots", arr);
		} else if (layout.equals("sheet")) {
			o = Images.sheet(shots, columns, cellWidth, format, quality);
		} else {
			throw RpcException.badRequest("layout doit valoir 'sheet' ou 'frames'.");
		}
		o.addProperty("layout", layout);
		o.addProperty("frameCount", frames);
		o.addProperty("intervalTicks", interval);
		o.addProperty("spanTicks", ticks.get(ticks.size() - 1) - ticks.get(0));
		return o;
	}

	// --- capture reutilisable ----------------------------------------------------------------------

	/**
	 * Masque ce qu'il faut, attend, capture la frame et renvoie le PNG brut.
	 *
	 * <p>Base commune a toutes les captures du mod ({@code vision.screenshot}, mode studio, captures
	 * d'interface). Le HUD et l'etat de masquage des ecrans sont toujours restaures, y compris en cas
	 * d'echec. {@code hideScreen} n'a d'effet que si un ecran est ouvert.
	 */
	public static byte[] captureRawPng(BridgeConfig cfg, Minecraft mc, boolean hideHud, boolean hideScreen,
	                                   int waitTicks) throws Exception {
		boolean[] previousHideGui = new boolean[1];
		try {
			ClientMc.call(() -> {
				CaptureState.hideScreens = hideScreen && mc.screen != null;
				previousHideGui[0] = mc.options.hideGui;
				mc.options.hideGui = hideHud;
				return null;
			});
			MainThread.await(TickWaiter.after(waitTicks), waitTicks * 50L + 5000L);

			CompletableFuture<byte[]> capture = new CompletableFuture<>();
			mc.execute(() -> {
				try {
					Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
						try {
							capture.complete(Images.toPng(image));
						} catch (Exception e) {
							capture.completeExceptionally(e);
						} finally {
							image.close();
							mc.options.hideGui = previousHideGui[0];
						}
					});
				} catch (Throwable t) {
					mc.options.hideGui = previousHideGui[0];
					capture.completeExceptionally(t);
				}
			});
			return MainThread.await(capture, Math.max(5000, cfg.callTimeoutMs));
		} finally {
			CaptureState.hideScreens = false;
			ClientMc.call(() -> {
				mc.options.hideGui = previousHideGui[0];
				return null;
			});
		}
	}

	/** Capture simple encodee, sans deplacement : utilisee par le mode studio. */
	public static JsonObject capture(BridgeConfig cfg, Minecraft mc, boolean hideHud, boolean hideScreen,
	                                 int waitTicks, int maxWidth, String format, double quality) throws Exception {
		byte[] png = captureRawPng(cfg, mc, hideHud, hideScreen, waitTicks);
		return Images.encode(png, maxWidth, format, quality);
	}

	// --- tool vision.screenshot --------------------------------------------------------------------

	private static JsonObject screenshot(RpcContext ctx) throws Exception {
		BridgeConfig cfg = McBridgeMod.config();
		if (!cfg.enableVision) throw RpcException.unavailable("La vision est desactivee (enableVision=false).");
		Minecraft mc = ClientMc.mc();
		if (mc.player == null || mc.level == null) throw RpcException.noPlayer();

		boolean hideHud = ctx.optBoolean("hideHud", true);
		boolean hideScreen = ctx.optBoolean("hideScreen", true);
		boolean closeScreen = ctx.optBoolean("closeScreen", false);
		// Sans cela, une capture demandee en l'air fait tomber un joueur en survie, avec les degats.
		boolean stabilize = ctx.optBoolean("stabilize", true);
		boolean returnToStart = ctx.optBoolean("returnToStart", false);
		int maxWidth = ctx.optInt("maxWidth", cfg.screenshot.defaultMaxWidth);
		String format = ctx.optString("format", cfg.screenshot.defaultFormat).toLowerCase(Locale.ROOT);
		double quality = ctx.optDouble("quality", cfg.screenshot.jpegQuality);
		JsonObject teleport = ctx.optObject("teleport");
		JsonObject look = ctx.optObject("look");
		int waitTicks = ctx.optInt("waitTicks",
				teleport != null ? Math.max(cfg.screenshot.defaultWaitTicks, 10) : cfg.screenshot.defaultWaitTicks);
		waitTicks = Math.max(1, Math.min(waitTicks, 600));
		if (teleport != null && !cfg.enableCommands) {
			throw RpcException.unavailable("La teleportation passe par une commande et enableCommands=false.");
		}

		// Deplacement avant la capture. Le spectateur evite la chute, et permet au passage de placer
		// la camera sans envoyer la moindre commande.
		Spectator.Guard guard = null;
		Vec3 startPos = null;
		float startYaw = 0;
		float startPitch = 0;
		if (teleport != null) {
			Object[] before = ClientMc.call(() -> {
				LocalPlayer p = ClientMc.player();
				return new Object[]{p.position(), p.getYRot(), p.getXRot()};
			});
			startPos = (Vec3) before[0];
			startYaw = (float) before[1];
			startPitch = (float) before[2];

			if (stabilize) guard = Spectator.enter();

			double eyeHeight = ClientMc.call(() -> (double) ClientMc.player().getEyeHeight());
			float tpYaw = teleport.has("yaw") ? teleport.get("yaw").getAsFloat() : startYaw;
			float tpPitch = teleport.has("pitch") ? teleport.get("pitch").getAsFloat() : startPitch;
			// Le parametre designe les pieds du joueur, comme une commande de teleportation.
			Vec3 eye = new Vec3(teleport.get("x").getAsDouble(),
					teleport.get("y").getAsDouble() + eyeHeight,
					teleport.get("z").getAsDouble());
			Commands.moveCamera(eye, tpYaw, tpPitch);
		}
		String[] screenOpen = new String[1];
		ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			if (look != null) {
				float yaw = look.has("yaw") ? look.get("yaw").getAsFloat() : p.getYRot();
				float pitch = look.has("pitch") ? look.get("pitch").getAsFloat() : p.getXRot();
				CameraHandlers.applyLook(p, yaw, pitch);
			}
			if (closeScreen && mc.screen != null) mc.setScreen(null);
			screenOpen[0] = mc.screen != null ? mc.screen.getClass().getSimpleName() : null;
			return null;
		});

		byte[] png = captureRawPng(cfg, mc, hideHud, hideScreen, waitTicks);
		JsonObject image = Images.encode(png, maxWidth, format, quality);

		// Position relevee ici, avant un eventuel retour au point de depart : sinon la reponse
		// annoncerait l'endroit ou le joueur a ete ramene, et non celui d'ou la vue a ete prise.
		Object[] shot = ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			return new Object[]{p.position(), p.getYRot(), p.getXRot(),
					mc.gameMode != null ? mc.gameMode.getPlayerMode().getSerializedName() : "unknown"};
		});
        final Vec3 shotPos = (Vec3) shot[0];
        final float shotYaw = (float) shot[1];
        final float shotPitch = (float) shot[2];
        final String shotMode = (String) shot[3];

		// Retour au point de depart si demande. Sinon le joueur reste sur place, et reste en
		// spectateur quand il a fallu l'y mettre : le remettre en survie en l'air le ferait tomber,
		// ce que ce garde-fou existe justement pour eviter.
		boolean returned = false;
		if (teleport != null && returnToStart) {
			double eyeHeight = ClientMc.call(() -> (double) ClientMc.player().getEyeHeight());
			Commands.moveCamera(startPos.add(0, eyeHeight, 0), startYaw, startPitch);
			Spectator.restore(guard);
			returned = true;
		}

		final boolean hidden = hideScreen;
		final Spectator.Guard finalGuard = guard;
		final boolean finalReturned = returned;
		JsonObject meta = new JsonObject();
		meta.add("playerPos", Json.vec(shotPos));
		meta.addProperty("yaw", shotYaw);
		meta.addProperty("pitch", shotPitch);
		meta.addProperty("gameMode", shotMode);
		meta.addProperty("tick", TickWaiter.currentTick());
		meta.addProperty("hudHidden", hideHud);
		meta.addProperty("screenOpen", screenOpen[0]);
		meta.addProperty("screenHidden", screenOpen[0] != null && hidden);
		if (finalGuard != null && finalGuard.changed()) {
			meta.addProperty("switchedToSpectator", true);
			meta.addProperty("previousGameMode", finalGuard.previousMode());
		}
		if (teleport != null) meta.addProperty("returnedToStart", finalReturned);
		image.add("capture", meta);
		return image;
	}
}
