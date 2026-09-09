package fr.tomda.mcbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre et dispatcher des methodes RPC.
 *
 * <p>Les methodes sont nommees {@code espace.action} (ex. {@code vision.screenshot}). Chaque
 * groupe de handlers s'enregistre dans son {@code register(RpcRouter)}. Le dispatch est appele
 * depuis les threads HTTP et renvoie toujours une enveloppe complete (jamais d'exception).
 */
public final class RpcRouter {
	private final Map<String, RpcHandler> handlers = new ConcurrentHashMap<>();

	public void register(String method, RpcHandler handler) {
		if (handlers.putIfAbsent(method, handler) != null) {
			McBridgeMod.LOGGER.warn("[mcbridge] methode RPC enregistree deux fois : {}", method);
		}
	}

	public boolean has(String method) {
		return handlers.containsKey(method);
	}

	/** Liste triee des methodes disponibles (pour info.status et le diagnostic). */
	public JsonArray methodNames() {
		JsonArray a = new JsonArray();
		new TreeMap<>(handlers).keySet().forEach(a::add);
		return a;
	}

	public JsonObject dispatch(String method, JsonObject params) {
		if (method == null || method.isBlank()) {
			return Json.envelopeError("bad_request", "Champ 'method' manquant.", null);
		}
		RpcHandler handler = handlers.get(method);
		if (handler == null) {
			return Json.envelopeError("unknown_method", "Methode inconnue : " + method, null);
		}
		try {
			JsonElement result = handler.handle(new RpcContext(method, params));
			return Json.envelopeOk(result);
		} catch (RpcException e) {
			return Json.envelopeError(e.code(), e.getMessage(), e.data());
		} catch (Throwable t) {
			McBridgeMod.LOGGER.error("[mcbridge] le handler '{}' a leve une exception", method, t);
			return Json.envelopeError("internal", t.getClass().getSimpleName() + ": " + t.getMessage(), null);
		}
	}
}
