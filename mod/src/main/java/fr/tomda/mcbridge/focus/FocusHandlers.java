package fr.tomda.mcbridge.focus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcContext;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.events.EventBus;
import fr.tomda.mcbridge.handlers.WorldHandlers;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.TickWaiter;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Pilotage du mode focus.
 * <ul>
 *   <li>{@code focus.set} : fusion. La selection d'entites est remplacee si uuids/ids/types est
 *       fourni ; chaque option de selection ou de scene n'est modifiee que si elle est presente.</li>
 *   <li>{@code focus.region} : definit ou retire ({@code clear:true}) la region, puis reconstruit
 *       les sections et attend la fin de la reconstruction (par defaut).</li>
 *   <li>{@code focus.clear} : remet tout a zero, reconstruit si une region etait active.</li>
 *   <li>{@code focus.status} : etat complet.</li>
 * </ul>
 */
public final class FocusHandlers {
	private FocusHandlers() {}

	public static void register(RpcRouter router, EventBus events) {
		router.register("focus.set", ctx -> {
			checkEnabled();
			FocusState f = FocusState.INSTANCE;

			Set<UUID> uuids = new HashSet<>();
			Set<Integer> ids = new HashSet<>();
			Set<String> types = new HashSet<>();
			boolean selectionGiven = ctx.has("uuids") || ctx.has("ids") || ctx.has("types");
			JsonArray a = ctx.optArray("uuids");
			if (a != null) {
				for (var x : a) {
					try {
						uuids.add(UUID.fromString(x.getAsString()));
					} catch (IllegalArgumentException e) {
						throw RpcException.badRequest("UUID invalide : " + x.getAsString());
					}
				}
			}
			a = ctx.optArray("ids");
			if (a != null) a.forEach(x -> ids.add(x.getAsInt()));
			a = ctx.optArray("types");
			if (a != null) a.forEach(x -> types.add(WorldHandlers.normalizeType(x.getAsString())));
			if (selectionGiven) f.setEntities(uuids, ids, types);

			f.setEntityOptions(
					ctx.has("attachRadius") ? ctx.getDouble("attachRadius") : null,
					ctx.optBooleanOrNull("includePassengers"),
					ctx.optBooleanOrNull("includeAttachedDisplays"),
					ctx.optBooleanOrNull("hideOthers"),
					ctx.optBooleanOrNull("hideSelf"),
					ctx.optBooleanOrNull("hideCameraOccluders"));
			// Le raccourci 'studio' allume d'un coup tout le masquage du decor. Sans lui, il est facile
			// d'oublier hideBlockEntities et de retrouver panneaux, coffres et bannieres sur l'image :
			// les block entities sont dessinees par une passe distincte de celle du terrain.
			Boolean studio = ctx.optBooleanOrNull("studio");
			Boolean preset = Boolean.TRUE.equals(studio) ? Boolean.TRUE : (Boolean.FALSE.equals(studio) ? Boolean.FALSE : null);
			f.setScene(
					firstNonNull(ctx.optBooleanOrNull("hideTerrain"), preset),
					firstNonNull(ctx.optBooleanOrNull("hideSky"), preset),
					firstNonNull(ctx.optBooleanOrNull("hideParticles"), preset),
					firstNonNull(ctx.optBooleanOrNull("hideBlockEntities"), preset),
					firstNonNull(ctx.optBooleanOrNull("disableFog"), preset),
					ctx.optBooleanOrNull("disableCameraClipping"),
					firstNonNull(ctx.optBooleanOrNull("hideInsideBlockOverlay"), preset),
					firstNonNull(ctx.optBooleanOrNull("flatLighting"), preset),
					ctx.optBooleanOrNull("hideAllEntities"));
			if (ctx.has("backgroundColor")) {
				try {
					f.setBackgroundColor(ctx.raw("backgroundColor").isJsonNull() ? null : ctx.getString("backgroundColor"));
				} catch (IllegalArgumentException e) {
					throw RpcException.badRequest(e.getMessage());
				}
			}
			return publish(events, refreshedStatus());
		});

		router.register("focus.region", ctx -> {
			checkEnabled();
			FocusState f = FocusState.INSTANCE;
			boolean hadRegion = f.isRegionActive();
			if (ctx.optBoolean("clear", false)) {
				f.clearRegion();
			} else {
				JsonObject from = ctx.optObject("from");
				JsonObject to = ctx.optObject("to");
				if (from == null || to == null) throw RpcException.badRequest("Fournir 'from' et 'to' ({x,y,z}), ou clear:true.");
				f.setRegion(from.get("x").getAsInt(), from.get("y").getAsInt(), from.get("z").getAsInt(),
						to.get("x").getAsInt(), to.get("y").getAsInt(), to.get("z").getAsInt(),
						ctx.optBoolean("hideEntitiesOutside", true));
			}
			JsonObject rebuild = (hadRegion || f.isRegionActive())
					? rebuildSections(ctx.optBoolean("waitForRebuild", true), ctx.optInt("timeoutMs", 20000))
					: null;
			JsonObject status = refreshedStatus();
			if (rebuild != null) status.add("rebuild", rebuild);
			return publish(events, status);
		});

		router.register("focus.clear", ctx -> {
			boolean hadRegion = FocusState.INSTANCE.isRegionActive();
			FocusState.INSTANCE.clear();
			JsonObject status = FocusState.INSTANCE.toJson();
			if (hadRegion) status.add("rebuild", rebuildSections(ctx.optBoolean("waitForRebuild", true), ctx.optInt("timeoutMs", 20000)));
			return publish(events, status);
		});

		router.register("focus.status", ctx -> FocusState.INSTANCE.toJson());
	}

	private static Boolean firstNonNull(Boolean explicit, Boolean fallback) {
		return explicit != null ? explicit : fallback;
	}

	private static void checkEnabled() throws RpcException {
		if (!McBridgeMod.config().enableFocus) throw RpcException.unavailable("Le mode focus est desactive (enableFocus=false).");
	}

	/** Force un calcul des racines sur le thread de rendu pour que selectedNow soit a jour. */
	private static JsonObject refreshedStatus() throws RpcException {
		return ClientMc.call(() -> {
			var mc = ClientMc.mc();
			if (mc.player != null) FocusState.INSTANCE.shouldRender(mc.player, 0, 0, 0);
			return FocusState.INSTANCE.toJson();
		});
	}

	private static JsonObject publish(EventBus events, JsonObject status) {
		events.emit("focus_changed", status.deepCopy());
		return status;
	}

	/**
	 * Reconstruit toutes les sections ({@code LevelRenderer.allChanged()}, que Sodium et Voxy
	 * interceptent aussi) et attend que le rendu soit complet, ou le timeout.
	 */
	private static JsonObject rebuildSections(boolean wait, int timeoutMs) throws RpcException {
		long start = System.currentTimeMillis();
		ClientMc.call(() -> {
			ClientMc.mc().levelRenderer.allChanged();
			return null;
		});
		boolean complete = false;
		if (wait) {
			// laisser le rechargement demarrer avant de tester
			MainThread.await(TickWaiter.after(5), 5000);
			while (System.currentTimeMillis() - start < timeoutMs) {
				complete = ClientMc.call(() -> ClientMc.mc().levelRenderer.hasRenderedAllSections());
				if (complete) break;
				MainThread.await(TickWaiter.after(4), 5000);
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("waited", wait);
		o.addProperty("complete", complete);
		o.addProperty("durationMs", System.currentTimeMillis() - start);
		return o;
	}
}
