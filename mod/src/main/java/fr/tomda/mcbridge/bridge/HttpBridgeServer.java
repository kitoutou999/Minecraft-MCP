package fr.tomda.mcbridge.bridge;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.events.EventBus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serveur HTTP embarque (JDK {@link HttpServer}, aucune dependance externe).
 *
 * <p>Routes :
 * <ul>
 *   <li>{@code GET  /health} : vivant, sans authentification</li>
 *   <li>{@code GET  /info}   : resultat de {@code info.status} (auth)</li>
 *   <li>{@code POST /rpc}    : {@code {method, params}} vers {@code {ok, result|error}} (auth)</li>
 *   <li>{@code GET  /events?types=a,b} : flux Server-Sent Events (auth)</li>
 * </ul>
 *
 * <p>Securite : ecoute sur 127.0.0.1 par defaut, jeton bearer obligatoire, comparaison en temps
 * constant. Les connexions dont l'adresse distante n'est pas locale sont refusees meme si
 * {@code host} a ete change, sauf si {@code allowRemote} est vrai dans la config.
 */
public final class HttpBridgeServer {
	private final BridgeConfig cfg;
	private final RpcRouter router;
	private final EventBus events;
	private final SseHub sse;
	private HttpServer http;

	public HttpBridgeServer(BridgeConfig cfg, RpcRouter router, EventBus events, SseHub sse) {
		this.cfg = cfg;
		this.router = router;
		this.events = events;
		this.sse = sse;
	}

	public void start() throws IOException {
		http = HttpServer.create(new InetSocketAddress(cfg.host, cfg.port), 0);
		AtomicInteger n = new AtomicInteger();
		ThreadFactory tf = r -> {
			Thread t = new Thread(r, "mcbridge-http-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
		http.setExecutor(Executors.newCachedThreadPool(tf));
		http.createContext("/health", this::handleHealth);
		http.createContext("/info", this::handleInfo);
		http.createContext("/rpc", this::handleRpc);
		http.createContext("/events", this::handleEvents);
		http.start();
		McBridgeMod.LOGGER.info("[mcbridge] bridge HTTP en ecoute sur http://{}:{}", cfg.host, cfg.port);
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
			http = null;
		}
	}

	private void handleHealth(HttpExchange ex) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("name", "mcbridge");
		o.addProperty("version", McBridgeMod.modVersion());
		respond(ex, 200, Json.GSON.toJson(o));
	}

	private void handleInfo(HttpExchange ex) throws IOException {
		if (!authorize(ex)) return;
		JsonObject env = router.dispatch("info.status", new JsonObject());
		respond(ex, 200, Json.GSON.toJson(env.has("result") ? env.get("result") : env));
	}

	private void handleRpc(HttpExchange ex) throws IOException {
		if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
			respond(ex, 405, Json.GSON.toJson(Json.envelopeError("method_not_allowed", "Utiliser POST.", null)));
			return;
		}
		if (!authorize(ex)) return;
		JsonObject req;
		try {
			req = Json.GSON.fromJson(readBody(ex), JsonObject.class);
		} catch (Exception e) {
			respond(ex, 400, Json.GSON.toJson(Json.envelopeError("bad_request", "Le corps doit etre un objet JSON.", null)));
			return;
		}
		if (req == null) {
			respond(ex, 400, Json.GSON.toJson(Json.envelopeError("bad_request", "Corps vide.", null)));
			return;
		}
		String method = req.has("method") && !req.get("method").isJsonNull() ? req.get("method").getAsString() : null;
		JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
		respond(ex, 200, Json.GSON.toJson(router.dispatch(method, params)));
	}

	private void handleEvents(HttpExchange ex) throws IOException {
		if (!authorize(ex)) return;
		Set<String> filter = parseTypeFilter(ex.getRequestURI().getQuery());
		ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.getResponseHeaders().set("Connection", "keep-alive");
		ex.sendResponseHeaders(200, 0);
		SseHub.Subscriber sub = sse.register(filter);
		try (OutputStream os = ex.getResponseBody()) {
			writeSse(os, ": connected, lastEventId=" + events.lastId() + "\n\n");
			while (true) {
				String event = sub.poll(15000);
				writeSse(os, event == null ? ": keepalive\n\n" : "data: " + event + "\n\n");
			}
		} catch (IOException | InterruptedException closed) {
			// client deconnecte
		} finally {
			sse.unregister(sub);
		}
	}

	private boolean authorize(HttpExchange ex) throws IOException {
		InetAddress remote = ex.getRemoteAddress().getAddress();
		if (!cfg.allowRemote && remote != null && !remote.isLoopbackAddress()) {
			respond(ex, 403, Json.GSON.toJson(Json.envelopeError("forbidden", "Connexions distantes refusees (allowRemote=false).", null)));
			return false;
		}
		if (!cfg.requireAuth || cfg.token == null || cfg.token.isBlank()) return true;
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		if (auth != null && constantTimeEquals(auth, "Bearer " + cfg.token)) return true;
		ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
		respond(ex, 401, Json.GSON.toJson(Json.envelopeError("unauthorized", "Jeton bearer manquant ou invalide.", null)));
		return false;
	}

	private static boolean constantTimeEquals(String a, String b) {
		byte[] x = a.getBytes(StandardCharsets.UTF_8);
		byte[] y = b.getBytes(StandardCharsets.UTF_8);
		if (x.length != y.length) return false;
		int r = 0;
		for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
		return r == 0;
	}

	private static Set<String> parseTypeFilter(String query) {
		Set<String> filter = new HashSet<>();
		if (query == null) return filter;
		for (String part : query.split("&")) {
			int eq = part.indexOf('=');
			if (eq > 0 && part.substring(0, eq).equals("types")) {
				for (String t : part.substring(eq + 1).split(",")) {
					if (!t.isBlank()) filter.add(t.trim());
				}
			}
		}
		return filter;
	}

	private static String readBody(HttpExchange ex) throws IOException {
		try (InputStream is = ex.getRequestBody()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void respond(HttpExchange ex, int code, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static void writeSse(OutputStream os, String s) throws IOException {
		os.write(s.getBytes(StandardCharsets.UTF_8));
		os.flush();
	}
}
