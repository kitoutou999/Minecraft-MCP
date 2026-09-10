package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.EntityJson;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lecture du monde tel que le client le connait (chunks charges, entites suivies).
 * Pour l'ecriture (setblock, summon...) : commandes via {@code chat.send} ou RCON cote serveur.
 */
public final class WorldHandlers {
	private WorldHandlers() {}

	public static void register(RpcRouter router) {
		router.register("world.getBlock", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			BlockPos pos = new BlockPos(ctx.getInt("x"), ctx.getInt("y"), ctx.getInt("z"));
			JsonObject o = new JsonObject();
			o.add("pos", Json.blockPos(pos));
			if (!level.isLoaded(pos)) {
				o.addProperty("loaded", false);
				return o;
			}
			BlockState st = level.getBlockState(pos);
			o.addProperty("loaded", true);
			o.addProperty("id", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
			o.addProperty("isAir", st.isAir());
			JsonObject props = new JsonObject();
			st.getValues().forEach(v -> props.addProperty(v.property().getName(), v.valueName()));
			o.add("properties", props);
			return o;
		}));

		router.register("world.entitiesNearby", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			double radius = ctx.optDouble("radius", 32.0);
			int max = ctx.optInt("max", 200);
			boolean includeDisplays = ctx.optBoolean("includeDisplays", true);
			boolean includePlayers = ctx.optBoolean("includePlayers", true);
			boolean includeSelf = ctx.optBoolean("includeSelf", false);
			boolean groupPassengers = ctx.optBoolean("groupPassengers", true);
			Set<String> types = new HashSet<>();
			JsonArray typesArr = ctx.optArray("types");
			if (typesArr != null) typesArr.forEach(t -> types.add(normalizeType(t.getAsString())));
			JsonObject centerObj = ctx.optObject("center");
			Vec3 center = centerObj != null
					? new Vec3(centerObj.get("x").getAsDouble(), centerObj.get("y").getAsDouble(), centerObj.get("z").getAsDouble())
					: p.position();

			List<Entity> found = new ArrayList<>();
			for (Entity e : level.entitiesForRendering()) {
				if (e == p && !includeSelf) continue;
				if (!includeDisplays && EntityJson.isDisplay(e)) continue;
				if (!includePlayers && e instanceof net.minecraft.world.entity.player.Player) continue;
				if (!types.isEmpty() && !types.contains(EntityJson.typeId(e))) continue;
				if (e.distanceToSqr(center) > radius * radius) continue;
				found.add(e);
			}
			found.sort((a, b) -> Double.compare(a.distanceToSqr(center), b.distanceToSqr(center)));

			// Un mob ModelEngine est une entite de base plus ses displays passagers : les lister un par
			// un repete huit fois la meme position pour un seul sujet, et remplit la liste. Les passagers
			// d'une entite deja listee sont replies dans son entree (passengerIds, displayPassengers) ;
			// un passager dont la monture n'est pas listee (filtree, hors rayon) reste visible.
			Set<Integer> listed = new HashSet<>();
			for (Entity e : found) listed.add(e.getId());
			List<Entity> roots = new ArrayList<>();
			int folded = 0;
			for (Entity e : found) {
				Entity vehicle = e.getVehicle();
				if (groupPassengers && vehicle != null && listed.contains(vehicle.getId())) {
					folded++;
					continue;
				}
				roots.add(e);
			}
			JsonArray arr = new JsonArray();
			for (int i = 0; i < roots.size() && i < max; i++) {
				Entity e = roots.get(i);
				JsonObject summary = EntityJson.summary(e, center);
				if (groupPassengers) {
					int displays = 0;
					for (Entity pe : e.getPassengers()) if (EntityJson.isDisplay(pe)) displays++;
					if (displays > 0) summary.addProperty("displayPassengers", displays);
				}
				arr.add(summary);
			}
			JsonObject o = new JsonObject();
			o.add("center", Json.vec(center));
			o.addProperty("total", roots.size());
			if (folded > 0) o.addProperty("foldedPassengers", folded);
			o.add("entities", arr);
			return o;
		}));

		router.register("world.getEntity", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			Entity target = findEntity(level, ctx.optString("uuid", null), ctx.has("id") ? ctx.getInt("id") : null);
			double attachRadius = ctx.optDouble("attachRadius", 4.0);
			JsonObject o = EntityJson.detailed(target, p.position());

			JsonArray passengers = new JsonArray();
			for (Entity pe : target.getPassengers()) passengers.add(EntityJson.detailed(pe, p.position()));
			o.add("passengers", passengers);

			// Displays proches : ModelEngine et Nexo positionnent leurs item_display autour de l'entite.
			JsonArray attached = new JsonArray();
			Vec3 c = target.position();
			for (Entity e : level.entitiesForRendering()) {
				if (e == target || !EntityJson.isDisplay(e)) continue;
				if (e.distanceToSqr(c) <= attachRadius * attachRadius) attached.add(EntityJson.detailed(e, c));
			}
			o.add("attachedDisplays", attached);
			return o;
		}));
	}

	/** Accepte "zombie" ou "minecraft:zombie". */
	public static String normalizeType(String t) {
		return t.contains(":") ? t : "minecraft:" + t;
	}

	public static Entity findEntity(ClientLevel level, String uuid, Integer id) throws RpcException {
		if (id != null) {
			Entity e = level.getEntity(id);
			if (e == null) throw RpcException.notFound("Aucune entite chargee avec l'id " + id);
			return e;
		}
		if (uuid != null) {
			UUID u;
			try {
				u = UUID.fromString(uuid);
			} catch (IllegalArgumentException ex) {
				throw RpcException.badRequest("UUID invalide : " + uuid);
			}
			for (Entity e : level.entitiesForRendering()) {
				if (u.equals(e.getUUID())) return e;
			}
			throw RpcException.notFound("Aucune entite chargee avec l'UUID " + uuid);
		}
		throw RpcException.badRequest("Fournir 'uuid' ou 'id'.");
	}
}
