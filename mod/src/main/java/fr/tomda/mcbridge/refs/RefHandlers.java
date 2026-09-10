package fr.tomda.mcbridge.refs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcContext;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.focus.FocusState;
import fr.tomda.mcbridge.handlers.VisionHandlers;
import fr.tomda.mcbridge.mixin.AbstractContainerScreenAccessor;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.Commands;
import fr.tomda.mcbridge.util.Excursion;
import fr.tomda.mcbridge.util.Images;
import fr.tomda.mcbridge.util.TickWaiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Images de reference et detection des regressions visuelles.
 *
 * <p>Sur un serveur avec des centaines d'objets et de modeles personnalises, corriger une texture
 * en casse souvent une autre sans qu'on s'en apercoive. Le principe : enregistrer une image de
 * reference avec la recette exacte qui l'a produite, puis, apres une modification du pack, rejouer
 * chaque recette et comparer pixel a pixel. {@code refs.compareAll} donne le verdict de toutes les
 * references en un appel, sans renvoyer d'image tant que rien n'a change.
 *
 * <p>Deux modes de recette :
 * <ul>
 *   <li>{@code world} : position et orientation de camera fixes, champ de vision et options de
 *       scene memorises. Entierement reproductible.</li>
 *   <li>{@code gui} : panneau de l'interface ouverte. La comparaison exige que le meme ecran soit
 *       ouvert, ce que le mod ne peut pas provoquer pour un menu de plugin.</li>
 * </ul>
 */
public final class RefHandlers {
	private RefHandlers() {}

	public static void register(RpcRouter router) {
		router.registerExclusive("refs.save", RefHandlers::save);
		router.registerExclusive("refs.compare", RefHandlers::compare);
		router.registerExclusive("refs.compareAll", RefHandlers::compareAll);
		router.register("refs.list", ctx -> {
			JsonArray arr = new JsonArray();
			try {
				for (String name : RefStore.list()) {
					JsonObject o = new JsonObject();
					o.addProperty("name", name);
					JsonObject recipe = RefStore.loadRecipe(name);
					o.addProperty("mode", recipe.has("mode") ? recipe.get("mode").getAsString() : "world");
					if (recipe.has("createdMs")) o.addProperty("createdMs", recipe.get("createdMs").getAsLong());
					if (recipe.has("note")) o.add("note", recipe.get("note"));
					if (recipe.has("resolution")) o.add("resolution", recipe.get("resolution"));
					arr.add(o);
				}
			} catch (IOException e) {
				throw RpcException.internal(e);
			}
			JsonObject o = new JsonObject();
			o.addProperty("directory", RefStore.directory().toString());
			o.add("references", arr);
			return o;
		});
		router.register("refs.delete", ctx -> {
			String name = RefStore.checkName(ctx.getString("name"));
			try {
				JsonObject o = new JsonObject();
				o.addProperty("deleted", RefStore.delete(name));
				o.addProperty("name", name);
				return o;
			} catch (IOException e) {
				throw RpcException.internal(e);
			}
		});
	}

	// --- enregistrement --------------------------------------------------------------------------

	private static JsonObject save(RpcContext ctx) throws Exception {
		BridgeConfig cfg = McBridgeMod.config();
		if (!cfg.enableVision) throw RpcException.unavailable("La vision est desactivee (enableVision=false).");
		String name = RefStore.checkName(ctx.getString("name"));
		if (RefStore.exists(name) && !ctx.optBoolean("overwrite", false)) {
			throw RpcException.badRequest("La reference '" + name + "' existe deja. Passer overwrite:true pour la remplacer.");
		}
		JsonObject recipe = buildRecipe(ctx, name);
		byte[] png = execute(recipe);
		try {
			RefStore.save(name, png, recipe);
		} catch (IOException e) {
			throw RpcException.internal(e);
		}
		JsonObject o = new JsonObject();
		o.addProperty("saved", true);
		o.addProperty("name", name);
		o.addProperty("imagePath", RefStore.imagePath(name).toString());
		o.addProperty("bytes", png.length);
		o.add("recipe", recipe);
		return o;
	}

	/** Construit la recette : les valeurs absentes sont prises sur l'etat courant du client. */
	private static JsonObject buildRecipe(RpcContext ctx, String name) throws RpcException {
		String mode = ctx.optString("mode", "world").toLowerCase(Locale.ROOT);
		if (!mode.equals("world") && !mode.equals("gui")) {
			throw RpcException.badRequest("mode doit valoir 'world' ou 'gui'.");
		}
		JsonObject recipe = new JsonObject();
		recipe.addProperty("name", name);
		recipe.addProperty("mode", mode);
		recipe.addProperty("createdMs", System.currentTimeMillis());
		if (ctx.has("note")) recipe.addProperty("note", ctx.getString("note"));
		recipe.addProperty("hideHud", ctx.optBoolean("hideHud", true));
		recipe.addProperty("waitTicks", Math.max(1, Math.min(ctx.optInt("waitTicks", 6), 200)));

		ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			JsonObject res = new JsonObject();
			res.addProperty("width", mc.getWindow().getWidth());
			res.addProperty("height", mc.getWindow().getHeight());
			res.addProperty("guiScale", mc.getWindow().getGuiScale());
			recipe.add("resolution", res);

			if (mode.equals("gui")) {
				if (!(mc.screen instanceof AbstractContainerScreen<?>)) {
					throw RpcException.badRequest("Le mode 'gui' demande un ecran de conteneur ouvert.");
				}
				recipe.addProperty("screen", mc.screen.getClass().getSimpleName());
				recipe.addProperty("title", mc.screen.getTitle() == null ? "" : mc.screen.getTitle().getString());
				return null;
			}

			LocalPlayer p = ClientMc.player();
			JsonObject cam = ctx.optObject("camera");
			JsonObject c = new JsonObject();
			c.addProperty("x", cam != null ? cam.get("x").getAsDouble() : p.getEyePosition().x);
			c.addProperty("y", cam != null ? cam.get("y").getAsDouble() : p.getEyePosition().y);
			c.addProperty("z", cam != null ? cam.get("z").getAsDouble() : p.getEyePosition().z);
			c.addProperty("yaw", cam != null && cam.has("yaw") ? cam.get("yaw").getAsFloat() : p.getYRot());
			c.addProperty("pitch", cam != null && cam.has("pitch") ? cam.get("pitch").getAsFloat() : p.getXRot());
			recipe.add("camera", c);
			recipe.addProperty("fov", ctx.has("fov") ? ctx.getInt("fov") : mc.options.fov().get());
			recipe.addProperty("dimension", p.level().dimension().identifier().toString());
			return null;
		});

		JsonObject scene = ctx.optObject("scene");
		if (scene != null) recipe.add("scene", scene.deepCopy());
		JsonObject focus = ctx.optObject("focus");
		if (focus != null) recipe.add("focus", focus.deepCopy());
		return recipe;
	}

	// --- execution d'une recette -----------------------------------------------------------------

	/**
	 * Rejoue une recette et renvoie le PNG obtenu.
	 *
	 * <p>Sauvegarde et restaure le focus, le champ de vision, le mode de jeu et la position :
	 * comparer des references ne doit rien laisser derriere soi.
	 */
	private static byte[] execute(JsonObject recipe) throws Exception {
		BridgeConfig cfg = McBridgeMod.config();
		Minecraft mc = ClientMc.mc();
		String mode = recipe.get("mode").getAsString();
		boolean hideHud = recipe.get("hideHud").getAsBoolean();
		int waitTicks = recipe.get("waitTicks").getAsInt();

		if (mode.equals("gui")) {
			String expected = recipe.get("screen").getAsString();
			int[] crop = ClientMc.call(() -> {
				if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
					throw RpcException.badRequest("Le mode 'gui' demande que l'ecran '" + expected + "' soit ouvert. "
							+ "Le mod ne peut pas ouvrir un menu de plugin : lancer sa commande d'abord.");
				}
				if (!screen.getClass().getSimpleName().equals(expected)) {
					throw RpcException.badRequest("Ecran ouvert '" + screen.getClass().getSimpleName()
							+ "' au lieu de '" + expected + "'.");
				}
				AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
				int scale = Math.max(1, mc.getWindow().getGuiScale());
				return new int[]{acc.mcbridge$leftPos() * scale, acc.mcbridge$topPos() * scale,
						acc.mcbridge$imageWidth() * scale, acc.mcbridge$imageHeight() * scale};
			});
			byte[] full = VisionHandlers.captureRawPng(cfg, mc, hideHud, false, waitTicks);
			JsonObject cropped = Images.encodeRegion(full, crop, 0, 0, "png", 1.0);
			return java.util.Base64.getDecoder().decode(cropped.get("base64").getAsString());
		}

		// Mode monde : placement de la camera puis capture, avec restauration complete.
		if (!cfg.enableCommands) {
			throw RpcException.unavailable("Le placement de la camera passe par une commande et enableCommands=false.");
		}
		// Une recette se rejoue a l'identique : meme point de vue, meme champ de vision, meme
		// isolement du sujet, et rien qui reste apres coup. Le champ de vision n'est force que si la
		// recette en porte un ; sinon celui du joueur convient et ne bouge pas.
		Integer recipeFov = recipe.has("fov") ? recipe.get("fov").getAsInt() : null;
		try (Excursion excursion = Excursion.begin(Excursion.options()
				.spectator(true)
				.fov(recipeFov)
				.restorePosition(true)
				.restoreMode(true)
				.restoreFocus(true))) {

			JsonObject scene = recipe.has("scene") ? recipe.getAsJsonObject("scene") : null;
			if (scene != null) {
				FocusState.INSTANCE.setScene(
						opt(scene, "hideTerrain"), opt(scene, "hideSky"), opt(scene, "hideParticles"),
						opt(scene, "hideBlockEntities"), opt(scene, "disableFog"), opt(scene, "disableCameraClipping"),
						opt(scene, "hideInsideBlockOverlay"), opt(scene, "flatLighting"), opt(scene, "hideAllEntities"));
				if (scene.has("backgroundColor") && !scene.get("backgroundColor").isJsonNull()) {
					try {
						FocusState.INSTANCE.setBackgroundColor(scene.get("backgroundColor").getAsString());
					} catch (IllegalArgumentException e) {
						throw RpcException.badRequest(e.getMessage());
					}
				}
			}

			// Isoler le sujet elimine le bruit des autres entites : sans cela, un joueur qui passe
			// dans le champ suffit a faire echouer la comparaison.
			JsonObject focus = recipe.has("focus") ? recipe.getAsJsonObject("focus") : null;
			if (focus != null) {
				java.util.Set<java.util.UUID> uuids = new java.util.HashSet<>();
				java.util.Set<Integer> ids = new java.util.HashSet<>();
				java.util.Set<String> types = new java.util.HashSet<>();
				if (focus.has("uuids")) {
					for (var x : focus.getAsJsonArray("uuids")) {
						try {
							uuids.add(java.util.UUID.fromString(x.getAsString()));
						} catch (IllegalArgumentException e) {
							throw RpcException.badRequest("UUID invalide dans la recette : " + x.getAsString());
						}
					}
				}
				if (focus.has("types")) {
					for (var x : focus.getAsJsonArray("types")) {
						String t = x.getAsString();
						types.add(t.contains(":") ? t : "minecraft:" + t);
					}
				}
				if (uuids.isEmpty() && types.isEmpty()) {
					throw RpcException.badRequest("Le focus d'une recette demande 'uuids' ou 'types'.");
				}
				FocusState.INSTANCE.setEntities(uuids, ids, types);
				FocusState.INSTANCE.setEntityOptions(
						focus.has("attachRadius") ? focus.get("attachRadius").getAsDouble() : 4.0,
						opt(focus, "includePassengers"), opt(focus, "includeAttachedDisplays"),
						opt(focus, "hideOthers"), opt(focus, "hideSelf"), opt(focus, "hideCameraOccluders"));
			}

			JsonObject cam = recipe.getAsJsonObject("camera");
			Vec3 wantedEye = new Vec3(cam.get("x").getAsDouble(), cam.get("y").getAsDouble(), cam.get("z").getAsDouble());
			Commands.moveCamera(wantedEye, cam.get("yaw").getAsFloat(), cam.get("pitch").getAsFloat());

			byte[] png = VisionHandlers.captureRawPng(cfg, mc, hideHud, true, waitTicks);

			Vec3 actual = ClientMc.call(() -> ClientMc.player().getEyePosition());
			if (actual.distanceTo(wantedEye) > 1.0) {
				throw new RpcException("teleport_failed", String.format(Locale.ROOT,
						"La camera n'a pas atteint la position de la recette (ecart %.2f bloc) : comparaison impossible.",
						actual.distanceTo(wantedEye)));
			}
			return png;
		}
	}

	private static Boolean opt(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : null;
	}


	// --- comparaison -----------------------------------------------------------------------------

	private static JsonObject compare(RpcContext ctx) throws Exception {
		String name = RefStore.checkName(ctx.getString("name"));
		if (!RefStore.exists(name)) throw RpcException.notFound("Reference inconnue : " + name);
		int tolerance = Math.max(0, Math.min(ctx.optInt("tolerance", 8), 255));
		boolean includeDiff = ctx.optBoolean("includeDiffImage", true);
		int diffMaxWidth = Math.max(0, Math.min(ctx.optInt("diffMaxWidth", DEFAULT_DIFF_MAX_WIDTH), 4096));
		double minPercent = Math.max(0, ctx.optDouble("diffImageMinPercent", DEFAULT_CHANGED_THRESHOLD_PERCENT));
		return compareOne(name, tolerance, includeDiff, diffMaxWidth, minPercent);
	}

	/** Largeur par defaut de l'image des differences : de quoi voir un detail sans payer une capture pleine. */
	private static final int DEFAULT_DIFF_MAX_WIDTH = 640;
	/**
	 * Part de pixels differents en deca de laquelle une reference est consideree inchangee. Une scene
	 * vivante a un bruit de fond : entites qui bougent, joueurs qui passent. Mesure sur le serveur de
	 * test : environ 0,2 % de pixels sans rien changer, contre 3 % pour une vraie difference. Le
	 * seuil se place entre les deux ; isoler le sujet avec 'focus' fait tomber le bruit a presque rien.
	 */
	private static final double DEFAULT_CHANGED_THRESHOLD_PERCENT = 0.5;

	private static final List<String> DIFF_IMAGE_KEYS = List.of(
			"diffBase64", "diffFormat", "diffMimeType", "diffBytes", "diffWidth", "diffHeight", "diffCrop");

	/**
	 * Rejoue une reference et la compare. L'image des differences n'est jointe qu'au-dela de
	 * {@code minPercent} : en dessous, elle ne montrerait que le bruit d'une scene vivante, pour le
	 * prix d'une image.
	 */
	private static JsonObject compareOne(String name, int tolerance, boolean includeDiff, int diffMaxWidth,
	                                     double minPercent) throws Exception {
		JsonObject recipe = RefStore.loadRecipe(name);
		byte[] reference = RefStore.loadImage(name);
		byte[] current = execute(recipe);
		JsonObject result = Images.diff(reference, current, tolerance, includeDiff, true, diffMaxWidth);
		double percent = result.get("percentDiffering").getAsDouble();
		if (result.has("diffBase64") && percent < minPercent) {
			for (String k : DIFF_IMAGE_KEYS) result.remove(k);
			result.addProperty("diffImageOmitted", String.format(Locale.ROOT,
					"%.3f %% de pixels differents, sous le seuil diffImageMinPercent de %.2f %% : bruit d'une scene "
							+ "vivante plutot qu'un changement. Baisser le seuil pour voir l'image quand meme.", percent, minPercent));
		}
		result.addProperty("name", name);
		result.add("recipe", recipe);
		return result;
	}

	private static JsonObject compareAll(RpcContext ctx) throws Exception {
		int tolerance = Math.max(0, Math.min(ctx.optInt("tolerance", 8), 255));
		// Une image de difference par reference saturerait la reponse : par defaut, seul le verdict
		// chiffre est renvoye, et l'appelant relance refs.compare sur celles qui ont bouge.
		boolean includeDiff = ctx.optBoolean("includeDiffImages", false);
		int diffMaxWidth = Math.max(0, Math.min(ctx.optInt("diffMaxWidth", DEFAULT_DIFF_MAX_WIDTH), 4096));
		double threshold = Math.max(0, ctx.optDouble("changedThresholdPercent", DEFAULT_CHANGED_THRESHOLD_PERCENT));
		List<String> names;
		try {
			names = RefStore.list();
		} catch (IOException e) {
			throw RpcException.internal(e);
		}
		// Passer une seule fois en spectateur pour toute la serie : chaque recette y verrait sinon un
		// changement de mode a faire et a defaire, soit deux commandes par reference, de quoi se faire
		// deconnecter pour spam sur une dizaine de references. La position n'a pas a etre restauree
		// ici, chaque comparaison ramenant deja le joueur d'ou elle l'a pris.
		Excursion excursion = names.isEmpty() ? null : Excursion.begin(Excursion.options()
				.spectator(true)
				.restoreMode(true));

		JsonArray results = new JsonArray();
		int changed = 0;
		int failed = 0;
		try {
		for (String name : names) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", name);
			try {
				// Le seuil de verdict sert aussi de seuil d'image : une reference declaree inchangee
				// n'a pas d'image a montrer.
				JsonObject d = compareOne(name, tolerance, includeDiff, diffMaxWidth, threshold);
				double percent = d.get("percentDiffering").getAsDouble();
				boolean hasChanged = percent >= threshold;
				if (hasChanged) changed++;
				entry.addProperty("changed", hasChanged);
				entry.addProperty("percentDiffering", percent);
				entry.addProperty("pixelsDiffering", d.get("pixelsDiffering").getAsLong());
				entry.addProperty("maxDelta", d.get("maxDelta").getAsInt());
				if (d.has("differenceBox")) entry.add("differenceBox", d.get("differenceBox"));
				if (d.get("resized").getAsBoolean()) entry.addProperty("resized", true);
				if (includeDiff && d.has("diffBase64")) {
					for (String k : DIFF_IMAGE_KEYS) if (d.has(k)) entry.add(k, d.get(k));
				}
			} catch (RpcException e) {
				failed++;
				entry.addProperty("error", e.code() + ": " + e.getMessage());
			}
			results.add(entry);
		}
		} finally {
			if (excursion != null) excursion.close();
		}
		JsonObject o = new JsonObject();
		o.addProperty("total", names.size());
		o.addProperty("changed", changed);
		o.addProperty("failed", failed);
		o.addProperty("tolerance", tolerance);
		o.addProperty("changedThresholdPercent", threshold);
		o.add("results", results);
		return o;
	}
}
