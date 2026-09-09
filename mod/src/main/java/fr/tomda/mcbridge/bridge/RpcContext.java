package fr.tomda.mcbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Contexte d'un appel RPC : nom de la methode et parametres JSON, avec des accesseurs types.
 *
 * <p>Les accesseurs {@code getX} levent {@code bad_request} si le parametre manque ; les
 * accesseurs {@code optX} renvoient la valeur par defaut.
 */
public final class RpcContext {
	private final String method;
	private final JsonObject params;

	public RpcContext(String method, JsonObject params) {
		this.method = method;
		this.params = params == null ? new JsonObject() : params;
	}

	public String method() {
		return method;
	}

	public JsonObject params() {
		return params;
	}

	public boolean has(String key) {
		return params.has(key) && !params.get(key).isJsonNull();
	}

	private JsonElement require(String key) throws RpcException {
		if (!has(key)) throw RpcException.badRequest("Parametre requis manquant : '" + key + "'.");
		return params.get(key);
	}

	public String getString(String key) throws RpcException {
		JsonElement e = require(key);
		if (!e.isJsonPrimitive()) throw RpcException.badRequest("Le parametre '" + key + "' doit etre une chaine.");
		return e.getAsString();
	}

	public String optString(String key, String def) {
		return has(key) && params.get(key).isJsonPrimitive() ? params.get(key).getAsString() : def;
	}

	public double getDouble(String key) throws RpcException {
		JsonElement e = require(key);
		try {
			return e.getAsDouble();
		} catch (Exception ex) {
			throw RpcException.badRequest("Le parametre '" + key + "' doit etre un nombre.");
		}
	}

	public double optDouble(String key, double def) {
		try {
			return has(key) ? params.get(key).getAsDouble() : def;
		} catch (Exception ex) {
			return def;
		}
	}

	public int getInt(String key) throws RpcException {
		JsonElement e = require(key);
		try {
			return e.getAsInt();
		} catch (Exception ex) {
			throw RpcException.badRequest("Le parametre '" + key + "' doit etre un entier.");
		}
	}

	public int optInt(String key, int def) {
		try {
			return has(key) ? params.get(key).getAsInt() : def;
		} catch (Exception ex) {
			return def;
		}
	}

	public boolean optBoolean(String key, boolean def) {
		try {
			return has(key) ? params.get(key).getAsBoolean() : def;
		} catch (Exception ex) {
			return def;
		}
	}

	/** Booleen nullable : null si absent (utile pour "ne pas modifier"). */
	public Boolean optBooleanOrNull(String key) {
		return has(key) ? params.get(key).getAsBoolean() : null;
	}

	public JsonObject optObject(String key) {
		return has(key) && params.get(key).isJsonObject() ? params.getAsJsonObject(key) : null;
	}

	public JsonArray optArray(String key) {
		return has(key) && params.get(key).isJsonArray() ? params.getAsJsonArray(key) : null;
	}

	public JsonElement raw(String key) {
		return params.get(key);
	}
}
