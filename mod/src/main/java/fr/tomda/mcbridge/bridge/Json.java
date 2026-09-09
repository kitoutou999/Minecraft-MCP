package fr.tomda.mcbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Utilitaires JSON : instance Gson partagee, enveloppes RPC et petits helpers de serialisation. */
public final class Json {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private Json() {}

	/** Enveloppe de succes : {@code {ok:true, result:<element>}}. */
	public static JsonObject envelopeOk(JsonElement result) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.add("result", result == null ? new JsonObject() : result);
		return o;
	}

	/** Enveloppe d'erreur : {@code {ok:false, error:{code,message,data?}}}. */
	public static JsonObject envelopeError(String code, String message, JsonElement data) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		if (data != null) err.add("data", data);
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.add("error", err);
		return o;
	}

	/** Petit resultat {@code {ok:true, message}} pour les actions sans donnee de retour. */
	public static JsonObject ok(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("message", message);
		return o;
	}

	public static JsonObject vec(Vec3 v) {
		return vec(v.x, v.y, v.z);
	}

	public static JsonObject vec(double x, double y, double z) {
		JsonObject o = new JsonObject();
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("z", z);
		return o;
	}

	public static JsonObject blockPos(BlockPos p) {
		JsonObject o = new JsonObject();
		o.addProperty("x", p.getX());
		o.addProperty("y", p.getY());
		o.addProperty("z", p.getZ());
		return o;
	}
}
