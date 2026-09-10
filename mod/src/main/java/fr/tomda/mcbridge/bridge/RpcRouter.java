package fr.tomda.mcbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registre et dispatcher des methodes RPC.
 *
 * <p>Les methodes sont nommees {@code espace.action} (ex. {@code vision.screenshot}). Chaque
 * groupe de handlers s'enregistre dans son {@code register(RpcRouter)}. Le dispatch est appele
 * depuis les threads HTTP et renvoie toujours une enveloppe complete (jamais d'exception).
 *
 * <p><b>Deux familles de methodes.</b> Une lecture ({@link #register}) peut s'executer en parallele
 * de n'importe quoi d'autre : elle ne fait que consulter. Une action exclusive
 * ({@link #registerExclusive}) prend le controle du client le temps de son execution : elle deplace
 * la camera, change le mode de jeu, masque le decor, force le champ de vision, puis restaure tout.
 * Deux actions de ce genre en parallele se marcheraient dessus, et la restauration de la premiere
 * effacerait le reglage de la seconde. Un client MCP unique en stdio n'en lance jamais deux a la
 * fois, mais rien ne l'empeche des que deux clients sont branches (Claude Code et Claude Desktop),
 * ou avec le transport HTTP qui accepte plusieurs sessions.
 *
 * <p>Les actions exclusives passent donc par un verrou unique. Un appel qui ne l'obtient pas dans
 * {@code busyTimeoutMs} echoue avec le code {@code busy} en nommant l'operation en cours, plutot
 * que d'attendre au-dela du delai du client MCP. Les lectures ne prennent jamais ce verrou : l'etat
 * du bridge reste consultable pendant une longue prise de vue.
 */
public final class RpcRouter {
	private final Map<String, RpcHandler> handlers = new ConcurrentHashMap<>();
	private final Set<String> exclusiveMethods = ConcurrentHashMap.newKeySet();
	private final ReentrantLock clientLock = new ReentrantLock(true);
	private final long busyTimeoutMs;

	/** Operation exclusive en cours, pour que le refus dise a qui la place est prise. */
	private volatile String currentOwner = null;
	private volatile long currentOwnerSince = 0;

	public RpcRouter(long busyTimeoutMs) {
		this.busyTimeoutMs = Math.max(0, busyTimeoutMs);
	}

	/** Methode de lecture : jamais serialisee, elle ne touche pas a l'etat partage du client. */
	public void register(String method, RpcHandler handler) {
		put(method, handler, false);
	}

	/**
	 * Methode qui prend le controle du client (camera, mode de jeu, focus, options de rendu,
	 * interface) : une seule a la fois.
	 */
	public void registerExclusive(String method, RpcHandler handler) {
		put(method, handler, true);
	}

	private void put(String method, RpcHandler handler, boolean exclusive) {
		if (handlers.putIfAbsent(method, handler) != null) {
			McBridgeMod.LOGGER.warn("[mcbridge] methode RPC enregistree deux fois : {}", method);
			return;
		}
		if (exclusive) exclusiveMethods.add(method);
	}

	public boolean has(String method) {
		return handlers.containsKey(method);
	}

	public boolean isExclusive(String method) {
		return exclusiveMethods.contains(method);
	}

	/** Liste triee des methodes disponibles (pour info.status et le diagnostic). */
	public JsonArray methodNames() {
		JsonArray a = new JsonArray();
		new TreeMap<>(handlers).keySet().forEach(a::add);
		return a;
	}

	/** Nom de l'action exclusive en cours, ou null si le client est libre. */
	public String currentOwner() {
		return currentOwner;
	}

	public JsonObject dispatch(String method, JsonObject params) {
		if (method == null || method.isBlank()) {
			return Json.envelopeError("bad_request", "Champ 'method' manquant.", null);
		}
		RpcHandler handler = handlers.get(method);
		if (handler == null) {
			return Json.envelopeError("unknown_method", "Methode inconnue : " + method, null);
		}
		if (!exclusiveMethods.contains(method)) return invoke(method, handler, params);

		boolean locked;
		try {
			locked = clientLock.tryLock(busyTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Json.envelopeError("interrupted", "Interrompu en attendant que le client soit libre.", null);
		}
		if (!locked) {
			String owner = currentOwner;
			long seconds = owner == null ? 0 : (System.currentTimeMillis() - currentOwnerSince) / 1000;
			return Json.envelopeError("busy", "Le client est deja occupe par '" + (owner == null ? "une autre action" : owner)
					+ "' depuis " + seconds + " s. Ces actions deplacent la camera et modifient le rendu : elles ne "
					+ "peuvent pas se derouler en meme temps. Reessayer apres, ou attendre la fin de l'appel en cours.", null);
		}
		String previousOwner = currentOwner;
		long previousSince = currentOwnerSince;
		currentOwner = method;
		currentOwnerSince = System.currentTimeMillis();
		try {
			return invoke(method, handler, params);
		} finally {
			// Restaure le proprietaire precedent : le verrou est reentrant, un handler pourrait
			// theoriquement en appeler un autre.
			currentOwner = clientLock.getHoldCount() > 1 ? previousOwner : null;
			currentOwnerSince = previousSince;
			clientLock.unlock();
		}
	}

	private JsonObject invoke(String method, RpcHandler handler, JsonObject params) {
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
