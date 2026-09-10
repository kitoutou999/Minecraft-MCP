package fr.tomda.mcbridge.reflect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.util.EntityJson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Serialisation des valeurs renvoyees par la reflexion, et desserialisation des arguments.
 *
 * <p>Les types connus (nombres, chaines, enums, collections, Vec3, BlockPos, BlockState,
 * ItemStack, Entity, Component, Identifier, AABB) deviennent du JSON lisible. Tout le reste
 * devient une reference opaque {@code {"__type":"object_ref","__id":"obj_N","__class":...}}
 * reutilisable comme {@code target} ou comme valeur d'argument.
 *
 * <p>Une poignee n'est creee que pour une classe autorisee par {@link PackageFilter}. Sans cela,
 * une methode autorisee renvoyant par exemple un {@code ProcessBuilder} donnerait prise sur lui
 * alors que la liste de blocage refuse de le charger directement. La valeur est alors decrite par
 * sa seule classe, sans poignee reutilisable.
 */
public final class Serializer {
	private static final int MAX_COLLECTION = 500;
	private final ObjectRegistry registry;
	private final PackageFilter filter;

	public Serializer(ObjectRegistry registry, PackageFilter filter) {
		this.registry = registry;
		this.filter = filter;
	}

	public JsonElement serialize(Object value) {
		return serialize(value, 0);
	}

	private JsonElement serialize(Object value, int depth) {
		if (value == null) return JsonNull.INSTANCE;
		if (value instanceof Boolean b) return new JsonPrimitive(b);
		if (value instanceof Number n) return new JsonPrimitive(n);
		if (value instanceof String s) return new JsonPrimitive(s);
		if (value instanceof Character c) return new JsonPrimitive(c);
		if (value instanceof Enum<?> e) return new JsonPrimitive(e.name());
		if (value instanceof Optional<?> opt) return opt.isPresent() ? serialize(opt.get(), depth) : JsonNull.INSTANCE;

		if (depth < 3) {
			if (value instanceof Collection<?> col) {
				JsonArray arr = new JsonArray();
				int i = 0;
				for (Object item : col) {
					if (i++ >= MAX_COLLECTION) break;
					arr.add(serialize(item, depth + 1));
				}
				return arr;
			}
			if (value instanceof Map<?, ?> map) {
				JsonObject obj = new JsonObject();
				int i = 0;
				for (Map.Entry<?, ?> e : map.entrySet()) {
					if (i++ >= MAX_COLLECTION) break;
					obj.add(String.valueOf(e.getKey()), serialize(e.getValue(), depth + 1));
				}
				return obj;
			}
			if (value.getClass().isArray()) {
				JsonArray arr = new JsonArray();
				int len = Math.min(Array.getLength(value), MAX_COLLECTION);
				for (int i = 0; i < len; i++) arr.add(serialize(Array.get(value, i), depth + 1));
				return arr;
			}
		}

		if (value instanceof Vec3 v) return Json.vec(v);
		if (value instanceof BlockPos bp) return Json.blockPos(bp);
		if (value instanceof Identifier id) return new JsonPrimitive(id.toString());
		if (value instanceof Component c) return new JsonPrimitive(c.getString());
		if (value instanceof AABB box) {
			JsonObject o = new JsonObject();
			o.add("min", Json.vec(box.minX, box.minY, box.minZ));
			o.add("max", Json.vec(box.maxX, box.maxY, box.maxZ));
			return o;
		}
		if (value instanceof BlockState bs) {
			JsonObject o = new JsonObject();
			o.addProperty("id", BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString());
			o.addProperty("state", bs.toString());
			return o;
		}
		if (value instanceof ItemStack is) {
			JsonObject o = new JsonObject();
			o.addProperty("item", BuiltInRegistries.ITEM.getKey(is.getItem()).toString());
			o.addProperty("count", is.getCount());
			o.addProperty("empty", is.isEmpty());
			return o;
		}
		if (value instanceof Entity e) {
			JsonObject o = EntityJson.summary(e, null);
			o.add("ref", ref(value));
			return o;
		}
		return ref(value);
	}

	private JsonObject ref(Object value) {
		String className = value.getClass().getName();
		JsonObject r = new JsonObject();
		r.addProperty("__type", "object_ref");
		r.addProperty("__class", className);
		if (!filter.isAllowed(className)) {
			// Pas de poignee : la garder permettrait d'appeler ensuite des methodes sur un objet dont
			// la classe est justement hors de la liste d'autorisation.
			r.addProperty("__stored", false);
			r.addProperty("__reason", "Classe hors liste d'autorisation : la valeur est decrite mais aucune poignee "
					+ "reutilisable n'est creee. Ajuster reflection.allowedPackages dans mcbridge.json si besoin.");
			return r;
		}
		r.addProperty("__id", registry.store(value));
		return r;
	}

	/** Reconstruit un argument : reference d'objet, variable {@code $nom}, ou primitive selon {@code typeName}. */
	public Object deserializeArg(JsonElement el, String typeName) {
		if (el == null || el.isJsonNull()) return null;
		if (el.isJsonObject()) {
			JsonObject obj = el.getAsJsonObject();
			if (obj.has("__id")) return registry.resolve(obj.get("__id").getAsString());
		}
		if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
			String s = el.getAsString();
			if (s.startsWith("$") || s.startsWith("obj_")) {
				Object resolved = registry.resolve(s);
				if (resolved != null || s.startsWith("$")) return resolved;
			}
		}
		return switch (typeName) {
			case "byte", "java.lang.Byte" -> el.getAsByte();
			case "short", "java.lang.Short" -> el.getAsShort();
			case "int", "java.lang.Integer" -> el.getAsInt();
			case "long", "java.lang.Long" -> el.getAsLong();
			case "float", "java.lang.Float" -> el.getAsFloat();
			case "double", "java.lang.Double" -> el.getAsDouble();
			case "boolean", "java.lang.Boolean" -> el.getAsBoolean();
			case "char", "java.lang.Character" -> el.getAsString().charAt(0);
			case "net.minecraft.core.BlockPos" -> {
				JsonObject o = el.getAsJsonObject();
				yield new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
			}
			case "net.minecraft.world.phys.Vec3" -> {
				JsonObject o = el.getAsJsonObject();
				yield new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
			}
			case "net.minecraft.resources.Identifier" -> Identifier.parse(el.getAsString());
			default -> el.isJsonPrimitive() ? el.getAsString() : el.toString();
		};
	}
}
