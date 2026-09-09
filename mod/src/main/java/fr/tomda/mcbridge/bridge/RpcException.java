package fr.tomda.mcbridge.bridge;

import com.google.gson.JsonElement;

/**
 * Erreur RPC typee, renvoyee au client MCP sous la forme {@code {ok:false, error:{code,message,data}}}.
 *
 * <p>Codes utilises dans le projet :
 * <ul>
 *   <li>{@code bad_request} : parametre manquant ou invalide</li>
 *   <li>{@code no_player} : le joueur n'est pas dans un monde</li>
 *   <li>{@code unavailable} : capacite desactivee dans la config ou non disponible</li>
 *   <li>{@code forbidden} : refuse par une liste d'autorisation (reflexion)</li>
 *   <li>{@code timeout} : le thread de jeu n'a pas repondu a temps</li>
 *   <li>{@code not_found} : entite, classe, fichier introuvable</li>
 *   <li>{@code internal} : exception non prevue</li>
 * </ul>
 */
public final class RpcException extends Exception {
	private final String code;
	private final JsonElement data;

	public RpcException(String code, String message) {
		this(code, message, null);
	}

	public RpcException(String code, String message, JsonElement data) {
		super(message);
		this.code = code;
		this.data = data;
	}

	public String code() {
		return code;
	}

	public JsonElement data() {
		return data;
	}

	public static RpcException badRequest(String message) {
		return new RpcException("bad_request", message);
	}

	public static RpcException noPlayer() {
		return new RpcException("no_player", "Le joueur n'est pas dans un monde (menu principal ou chargement en cours).");
	}

	public static RpcException unavailable(String message) {
		return new RpcException("unavailable", message);
	}

	public static RpcException forbidden(String message) {
		return new RpcException("forbidden", message);
	}

	public static RpcException notFound(String message) {
		return new RpcException("not_found", message);
	}

	public static RpcException internal(Throwable t) {
		return new RpcException("internal", t.getClass().getSimpleName() + ": " + t.getMessage());
	}
}
