package fr.tomda.mcbridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Serialisation des entites pour le MCP.
 *
 * <p>Une entite "display" ({@code item_display}, {@code block_display}, {@code text_display},
 * {@code interaction}) est signalee par {@code isDisplay=true} : ModelEngine et Nexo construisent
 * leurs modeles avec ces entites, c'est l'information dont le mode focus a besoin.
 */
public final class EntityJson {
	private EntityJson() {}

	public static String typeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	public static boolean isDisplay(Entity e) {
		return e instanceof Display || e instanceof Interaction;
	}

	/** Resume compact d'une entite ; {@code from} sert a calculer la distance (nullable). */
	public static JsonObject summary(Entity e, Vec3 from) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.getId());
		o.addProperty("uuid", e.getStringUUID());
		o.addProperty("type", typeId(e));
		o.addProperty("name", e.getName().getString());
		if (e.getCustomName() != null) o.addProperty("customName", e.getCustomName().getString());
		o.add("pos", Json.vec(e.position()));
		o.addProperty("yaw", e.getYRot());
		o.addProperty("pitch", e.getXRot());
		o.addProperty("isDisplay", isDisplay(e));
		o.addProperty("invisible", e.isInvisible());
		if (from != null) o.addProperty("distance", Math.sqrt(e.distanceToSqr(from)));
		if (e.getVehicle() != null) o.addProperty("vehicleId", e.getVehicle().getId());
		if (!e.getPassengers().isEmpty()) {
			JsonArray p = new JsonArray();
			for (Entity pe : e.getPassengers()) p.add(pe.getId());
			o.add("passengerIds", p);
		}
		return o;
	}

	/** Resume + boite englobante (pour le cadrage camera). */
	public static JsonObject detailed(Entity e, Vec3 from) {
		JsonObject o = summary(e, from);
		AABB box = e.getBoundingBox();
		JsonObject b = new JsonObject();
		b.add("min", Json.vec(box.minX, box.minY, box.minZ));
		b.add("max", Json.vec(box.maxX, box.maxY, box.maxZ));
		b.add("center", Json.vec(box.getCenter()));
		b.addProperty("size", box.getSize());
		o.add("boundingBox", b);
		// Taille declaree pour le rendu : sur un display, c'est la seule mesure du modele affiche,
		// la hitbox etant souvent reduite a un point.
		AABB culling = e instanceof Display display ? display.getBoundingBoxForCulling() : null;
		if (culling != null && !culling.equals(box)) {
			JsonObject c = new JsonObject();
			c.add("min", Json.vec(culling.minX, culling.minY, culling.minZ));
			c.add("max", Json.vec(culling.maxX, culling.maxY, culling.maxZ));
			c.add("size", Json.vec(culling.getXsize(), culling.getYsize(), culling.getZsize()));
			o.add("cullingBox", c);
		}
		return o;
	}
}
