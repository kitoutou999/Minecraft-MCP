package fr.tomda.mcbridge;

import fr.tomda.mcbridge.bridge.HttpBridgeServer;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.bridge.SseHub;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.events.ChatLog;
import fr.tomda.mcbridge.events.ClientEvents;
import fr.tomda.mcbridge.events.EventBus;
import fr.tomda.mcbridge.focus.FocusHandlers;
import fr.tomda.mcbridge.gui.GuiHandlers;
import fr.tomda.mcbridge.handlers.CameraHandlers;
import fr.tomda.mcbridge.handlers.ChatHandlers;
import fr.tomda.mcbridge.handlers.ClientOptionHandlers;
import fr.tomda.mcbridge.handlers.EventHandlers;
import fr.tomda.mcbridge.handlers.GameHandlers;
import fr.tomda.mcbridge.handlers.InfoHandlers;
import fr.tomda.mcbridge.handlers.LogHandlers;
import fr.tomda.mcbridge.handlers.PlayerHandlers;
import fr.tomda.mcbridge.handlers.ResourceHandlers;
import fr.tomda.mcbridge.handlers.VisionHandlers;
import fr.tomda.mcbridge.handlers.WorldHandlers;
import fr.tomda.mcbridge.reflect.ReflectionHandlers;
import fr.tomda.mcbridge.refs.RefHandlers;
import fr.tomda.mcbridge.server.ServerHandlers;
import fr.tomda.mcbridge.studio.StudioHandlers;
import fr.tomda.mcbridge.util.Rcon;
import fr.tomda.mcbridge.util.TickWaiter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Point d'entree client du mod.
 *
 * <p>Demarre le bridge HTTP local et enregistre tous les handlers RPC dans un routeur partage.
 * Ajouter un groupe de tools = creer une classe {@code XxxHandlers} avec un {@code register},
 * l'appeler ici, puis declarer les tools correspondants dans {@code mcp-server/src/tools.ts}
 * (voir CLAUDE.md, section "Ajouter un tool").
 */
public class McBridgeMod implements ClientModInitializer {
	public static final String MOD_ID = "mcbridge";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static String modVersion;
	private static String mcVersion;

	private static BridgeConfig config;
	private static RpcRouter router;
	private static EventBus events;
	private static HttpBridgeServer http;

	/**
	 * Version du mod, lue a la demande.
	 *
	 * <p>Lire {@link FabricLoader} au chargement de la classe rendrait tout test hors du jeu
	 * impossible : l'initialisation statique echouerait avant meme d'atteindre le code teste.
	 */
	public static String modVersion() {
		if (modVersion == null) {
			modVersion = FabricLoader.getInstance().getModContainer(MOD_ID)
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		}
		return modVersion;
	}

	public static String mcVersion() {
		if (mcVersion == null) {
			mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		}
		return mcVersion;
	}

	public static BridgeConfig config() {
		return config;
	}

	public static RpcRouter router() {
		return router;
	}

	public static EventBus events() {
		return events;
	}

	@Override
	public void onInitializeClient() {
		config = BridgeConfig.load();
		SseHub sse = new SseHub();
		events = new EventBus(sse);
		ChatLog chat = new ChatLog();
		router = new RpcRouter(config.busyTimeoutMs);

		TickWaiter.register();
		ClientEvents.register(events, chat);

		InfoHandlers.register(router);
		PlayerHandlers.register(router);
		CameraHandlers.register(router);
		VisionHandlers.register(router);
		WorldHandlers.register(router);
		ChatHandlers.register(router, chat);
		EventHandlers.register(router, events);
		ClientOptionHandlers.register(router);
		ResourceHandlers.register(router, events);
		LogHandlers.register(router);
		GameHandlers.register(router);
		FocusHandlers.register(router, events);
		GuiHandlers.register(router);
		StudioHandlers.register(router);
		RefHandlers.register(router);
		ServerHandlers.register(router);
		ReflectionHandlers.register(router, config.reflection);

		http = new HttpBridgeServer(config, router, events, sse);
		try {
			http.start();
			LOGGER.info("[mcbridge] pret : bridge http://{}:{} ({} methodes), jeton dans {}",
					config.host, config.port, router.methodNames().size(), config.source);
		} catch (Exception e) {
			LOGGER.error("[mcbridge] impossible de demarrer le bridge sur {}:{}", config.host, config.port, e);
		}

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (http != null) http.stop();
			Rcon.shutdown();
		});
	}
}
