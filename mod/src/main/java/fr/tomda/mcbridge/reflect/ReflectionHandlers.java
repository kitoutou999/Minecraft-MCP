package fr.tomda.mcbridge.reflect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.RpcContext;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.util.ClientMc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * Reflexion sur le client : l'echappatoire quand aucun tool dedie n'existe.
 *
 * <p>Toute invocation s'execute sur le thread de rendu. Les classes sont filtrees par
 * {@link PackageFilter} (config {@code reflection.allowedPackages} / {@code blockedPackages}).
 * Exemple : lire {@code Minecraft.getInstance().options.hideGui} =
 * {@code reflect.invoke(className="net.minecraft.client.Minecraft", methodName="getInstance", assignTo="mc")}
 * puis {@code reflect.getField(className="net.minecraft.client.Minecraft", fieldName="options", target="$mc", assignTo="opt")}
 * puis {@code reflect.getField(className="net.minecraft.client.Options", fieldName="hideGui", target="$opt")}.
 */
public final class ReflectionHandlers {
	private final ObjectRegistry registry;
	private final Serializer serializer;
	private final PackageFilter filter;

	private ReflectionHandlers(BridgeConfig.Reflection cfg) {
		this.registry = new ObjectRegistry(cfg.maxObjectRefs);
		this.filter = new PackageFilter(cfg.allowedPackages, cfg.blockedPackages);
		this.serializer = new Serializer(registry, filter);
	}

	public static void register(RpcRouter router, BridgeConfig.Reflection cfg) {
		ReflectionHandlers h = new ReflectionHandlers(cfg);
		router.register("reflect.invoke", h::invoke);
		router.register("reflect.getField", h::getField);
		router.register("reflect.setField", h::setField);
		router.register("reflect.newInstance", h::newInstance);
		router.register("reflect.classInfo", h::classInfo);
		router.register("vars.get", ctx -> {
			Object v = h.registry.resolve("$" + ctx.getString("name"));
			return v == null ? JsonNull.INSTANCE : h.serializer.serialize(v);
		});
		router.register("vars.list", ctx -> {
			JsonObject out = new JsonObject();
			for (Map.Entry<String, Object> e : h.registry.variables().entrySet()) {
				out.addProperty(e.getKey(), e.getValue() == null ? "null" : e.getValue().getClass().getName());
			}
			return out;
		});
		router.register("vars.delete", ctx -> {
			h.registry.delete(ctx.getString("name"));
			return Json.ok("Variable supprimee.");
		});
		router.register("vars.clear", ctx -> {
			h.registry.clear();
			return Json.ok("Variables et poignees effacees.");
		});
	}

	private void checkEnabled() throws RpcException {
		if (!McBridgeMod.config().enableReflection) {
			throw RpcException.unavailable("La reflexion est desactivee (enableReflection=false).");
		}
	}

	private Class<?> loadClass(String className) throws RpcException {
		checkEnabled();
		if (!filter.isAllowed(className)) throw RpcException.forbidden("Classe hors liste d'autorisation : " + className);
		try {
			return Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw RpcException.notFound("Classe introuvable : " + className);
		}
	}

	private Object[] buildArgs(JsonArray argsArr, Class<?>[] typesOut) {
		Object[] values = new Object[argsArr.size()];
		for (int i = 0; i < argsArr.size(); i++) {
			JsonObject a = argsArr.get(i).getAsJsonObject();
			String type = a.has("type") ? a.get("type").getAsString() : "java.lang.String";
			values[i] = serializer.deserializeArg(a.get("value"), type);
			typesOut[i] = resolveType(type, values[i]);
		}
		return values;
	}

	private JsonElement invoke(RpcContext ctx) throws Exception {
		Class<?> cls = loadClass(ctx.getString("className"));
		String methodName = ctx.getString("methodName");
		JsonArray argsArr = ctx.optArray("args") != null ? ctx.optArray("args") : new JsonArray();
		String target = ctx.optString("target", null);
		String assignTo = ctx.optString("assignTo", null);

		Class<?>[] argTypes = new Class<?>[argsArr.size()];
		Object[] argValues = buildArgs(argsArr, argTypes);
		Object instance = target != null ? registry.resolve(target) : null;
		if (target != null && instance == null) throw RpcException.notFound("Cible inconnue : " + target);
		Method method = findMethod(cls, methodName, argTypes);
		method.setAccessible(true);

		Object result = ClientMc.call(30000, () -> method.invoke(instance, argValues));
		if (assignTo != null) registry.assign(assignTo, result);
		return serializer.serialize(result);
	}

	private JsonElement getField(RpcContext ctx) throws Exception {
		Class<?> cls = loadClass(ctx.getString("className"));
		Field field = findField(cls, ctx.getString("fieldName"));
		field.setAccessible(true);
		String target = ctx.optString("target", null);
		String assignTo = ctx.optString("assignTo", null);
		Object instance = target != null ? registry.resolve(target) : null;
		if (target != null && instance == null) throw RpcException.notFound("Cible inconnue : " + target);
		Object result = ClientMc.call(() -> field.get(instance));
		if (assignTo != null) registry.assign(assignTo, result);
		return serializer.serialize(result);
	}

	private JsonElement setField(RpcContext ctx) throws Exception {
		Class<?> cls = loadClass(ctx.getString("className"));
		Field field = findField(cls, ctx.getString("fieldName"));
		field.setAccessible(true);
		String target = ctx.optString("target", null);
		Object instance = target != null ? registry.resolve(target) : null;
		if (target != null && instance == null) throw RpcException.notFound("Cible inconnue : " + target);
		Object value = serializer.deserializeArg(ctx.raw("value"), ctx.optString("valueType", field.getType().getName()));
		ClientMc.call(() -> {
			field.set(instance, value);
			return null;
		});
		return Json.ok("Champ modifie.");
	}

	private JsonElement newInstance(RpcContext ctx) throws Exception {
		Class<?> cls = loadClass(ctx.getString("className"));
		JsonArray argsArr = ctx.optArray("args") != null ? ctx.optArray("args") : new JsonArray();
		String assignTo = ctx.optString("assignTo", null);
		Class<?>[] argTypes = new Class<?>[argsArr.size()];
		Object[] argValues = buildArgs(argsArr, argTypes);
		Constructor<?> ctor = findConstructor(cls, argTypes);
		ctor.setAccessible(true);
		Object instance = ClientMc.call(() -> ctor.newInstance(argValues));
		if (assignTo != null) registry.assign(assignTo, instance);
		return serializer.serialize(instance);
	}

	private JsonElement classInfo(RpcContext ctx) throws Exception {
		Class<?> cls = loadClass(ctx.getString("className"));
		boolean inherited = ctx.optBoolean("includeInherited", false);
		String nameFilter = ctx.optString("filter", null);
		JsonObject out = new JsonObject();
		out.addProperty("name", cls.getName());
		out.addProperty("superclass", cls.getSuperclass() != null ? cls.getSuperclass().getName() : null);
		JsonArray ma = new JsonArray();
		for (Method m : inherited ? cls.getMethods() : cls.getDeclaredMethods()) {
			if (nameFilter != null && !m.getName().toLowerCase().contains(nameFilter.toLowerCase())) continue;
			JsonObject mo = new JsonObject();
			mo.addProperty("name", m.getName());
			mo.addProperty("modifiers", Modifier.toString(m.getModifiers()));
			mo.addProperty("returnType", m.getReturnType().getName());
			JsonArray pt = new JsonArray();
			for (Class<?> c : m.getParameterTypes()) pt.add(c.getName());
			mo.add("parameterTypes", pt);
			ma.add(mo);
		}
		out.add("methods", ma);
		JsonArray fa = new JsonArray();
		for (Field f : inherited ? cls.getFields() : cls.getDeclaredFields()) {
			if (nameFilter != null && !f.getName().toLowerCase().contains(nameFilter.toLowerCase())) continue;
			JsonObject fo = new JsonObject();
			fo.addProperty("name", f.getName());
			fo.addProperty("type", f.getType().getName());
			fo.addProperty("modifiers", Modifier.toString(f.getModifiers()));
			fa.add(fo);
		}
		out.add("fields", fa);
		return out;
	}

	// --- resolution ------------------------------------------------------------------------------

	private static Method findMethod(Class<?> cls, String name, Class<?>[] argTypes) throws RpcException {
		Method fallback = null;
		for (Method[] set : new Method[][]{cls.getMethods(), cls.getDeclaredMethods()}) {
			for (Method m : set) {
				if (!m.getName().equals(name) || m.getParameterCount() != argTypes.length) continue;
				if (compatible(m.getParameterTypes(), argTypes)) return m;
				if (fallback == null) fallback = m;
			}
		}
		if (fallback != null) return fallback;
		throw RpcException.notFound(cls.getName() + "." + name + " avec " + argTypes.length + " argument(s) introuvable. Utiliser reflect.classInfo pour lister les methodes.");
	}

	private static Constructor<?> findConstructor(Class<?> cls, Class<?>[] argTypes) throws RpcException {
		Constructor<?> fallback = null;
		for (Constructor<?> c : cls.getDeclaredConstructors()) {
			if (c.getParameterCount() != argTypes.length) continue;
			if (compatible(c.getParameterTypes(), argTypes)) return c;
			if (fallback == null) fallback = c;
		}
		if (fallback != null) return fallback;
		throw RpcException.notFound("Aucun constructeur de " + cls.getName() + " a " + argTypes.length + " argument(s).");
	}

	private static Field findField(Class<?> cls, String name) throws RpcException {
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				// remonter la hierarchie
			}
		}
		throw RpcException.notFound("Champ " + cls.getName() + "." + name + " introuvable.");
	}

	private static boolean compatible(Class<?>[] params, Class<?>[] args) {
		for (int i = 0; i < params.length; i++) {
			if (args[i] == null) continue;
			if (!boxed(params[i]).isAssignableFrom(boxed(args[i]))) return false;
		}
		return true;
	}

	private static Class<?> boxed(Class<?> c) {
		if (!c.isPrimitive()) return c;
		if (c == int.class) return Integer.class;
		if (c == long.class) return Long.class;
		if (c == double.class) return Double.class;
		if (c == float.class) return Float.class;
		if (c == boolean.class) return Boolean.class;
		if (c == byte.class) return Byte.class;
		if (c == short.class) return Short.class;
		if (c == char.class) return Character.class;
		return c;
	}

	private Class<?> resolveType(String typeName, Object value) {
		switch (typeName) {
			case "byte": return byte.class;
			case "short": return short.class;
			case "int": return int.class;
			case "long": return long.class;
			case "float": return float.class;
			case "double": return double.class;
			case "boolean": return boolean.class;
			case "char": return char.class;
			default:
				try {
					if (filter.isAllowed(typeName)) return Class.forName(typeName);
				} catch (ClassNotFoundException ignored) {
					// on retombe sur la classe de la valeur
				}
				return value != null ? value.getClass() : Object.class;
		}
	}
}
