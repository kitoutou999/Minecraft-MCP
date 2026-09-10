package fr.tomda.mcbridge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serialisation des actions qui prennent le controle du client.
 *
 * <p>Deux captures en parallele se marcheraient dessus : la restauration de la premiere effacerait
 * le reglage de la seconde, et l'image renvoyee ne correspondrait a aucune demande. Une lecture,
 * elle, doit rester possible a tout moment, y compris pendant une longue prise de vue.
 */
class RpcRouterTest {

	private static String code(JsonObject envelope) {
		return envelope.getAsJsonObject("error").get("code").getAsString();
	}

	@Test
	@DisplayName("une methode inconnue et une methode absente sont refusees proprement")
	void unknownMethodIsRejected() {
		RpcRouter router = new RpcRouter(1000);
		assertEquals("unknown_method", code(router.dispatch("rien.du.tout", new JsonObject())));
		assertEquals("bad_request", code(router.dispatch(null, new JsonObject())));
		assertEquals("bad_request", code(router.dispatch("  ", new JsonObject())));
	}

	@Test
	@DisplayName("une erreur du handler devient une enveloppe, jamais une exception")
	void handlerFailureBecomesEnvelope() {
		RpcRouter router = new RpcRouter(1000);
		router.register("test.rpc", ctx -> {
			throw RpcException.notFound("absent");
		});
		router.register("test.crash", ctx -> {
			throw new IllegalStateException("boum");
		});
		assertEquals("not_found", code(router.dispatch("test.rpc", new JsonObject())));
		assertEquals("internal", code(router.dispatch("test.crash", new JsonObject())));
	}

	@Test
	@DisplayName("le resultat d'un handler est enveloppe tel quel")
	void resultIsWrapped() {
		RpcRouter router = new RpcRouter(1000);
		router.register("test.ok", ctx -> new JsonPrimitive(42));
		JsonObject env = router.dispatch("test.ok", new JsonObject());
		assertTrue(env.get("ok").getAsBoolean());
		assertEquals(42, env.get("result").getAsInt());
	}

	@Test
	@DisplayName("deux actions exclusives ne s'executent jamais en meme temps")
	void exclusiveActionsNeverOverlap() throws Exception {
		RpcRouter router = new RpcRouter(5000);
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger maxInside = new AtomicInteger();
		AtomicInteger completed = new AtomicInteger();
		router.registerExclusive("test.capture", ctx -> {
			int now = inside.incrementAndGet();
			maxInside.updateAndGet(m -> Math.max(m, now));
			Thread.sleep(40);
			inside.decrementAndGet();
			completed.incrementAndGet();
			return new JsonObject();
		});

		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		for (int i = 0; i < threads; i++) {
			Thread t = new Thread(() -> {
				try {
					start.await();
					router.dispatch("test.capture", new JsonObject());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
			t.setDaemon(true);
			t.start();
		}
		start.countDown();
		assertTrue(done.await(10, TimeUnit.SECONDS), "les appels doivent tous aboutir");
		assertEquals(1, maxInside.get(), "jamais deux actions exclusives ensemble");
		assertEquals(threads, completed.get(), "aucun appel perdu quand le delai est suffisant");
	}

	@Test
	@DisplayName("une action refusee faute de place dit qui occupe le client")
	void busyRefusalNamesTheCurrentAction() throws Exception {
		RpcRouter router = new RpcRouter(30);
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		router.registerExclusive("studio.frameTarget", ctx -> {
			running.countDown();
			release.await(5, TimeUnit.SECONDS);
			return new JsonObject();
		});

		Thread holder = new Thread(() -> router.dispatch("studio.frameTarget", new JsonObject()));
		holder.setDaemon(true);
		holder.start();
		assertTrue(running.await(5, TimeUnit.SECONDS));

		JsonObject env = router.dispatch("studio.frameTarget", new JsonObject());
		assertFalse(env.get("ok").getAsBoolean());
		assertEquals("busy", code(env));
		String message = env.getAsJsonObject("error").get("message").getAsString();
		assertTrue(message.contains("studio.frameTarget"), "le refus doit nommer l'action en cours : " + message);

		release.countDown();
		holder.join(5000);
	}

	@Test
	@DisplayName("une lecture reste possible pendant une action exclusive")
	void readsAreNeverBlocked() throws Exception {
		RpcRouter router = new RpcRouter(30);
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		router.registerExclusive("vision.screenshot", ctx -> {
			running.countDown();
			release.await(5, TimeUnit.SECONDS);
			return new JsonObject();
		});
		router.register("info.status", ctx -> new JsonPrimitive("vivant"));

		Thread holder = new Thread(() -> router.dispatch("vision.screenshot", new JsonObject()));
		holder.setDaemon(true);
		holder.start();
		assertTrue(running.await(5, TimeUnit.SECONDS));

		long before = System.currentTimeMillis();
		JsonObject env = router.dispatch("info.status", new JsonObject());
		long elapsed = System.currentTimeMillis() - before;
		assertTrue(env.get("ok").getAsBoolean(), "l'etat doit rester consultable pendant une capture");
		assertEquals("vivant", env.get("result").getAsString());
		assertTrue(elapsed < 1000, "la lecture ne doit pas attendre le verrou (" + elapsed + " ms)");

		release.countDown();
		holder.join(5000);
	}

	@Test
	@DisplayName("le client redevient libre apres une action, meme si elle echoue")
	void lockIsReleasedAfterFailure() {
		RpcRouter router = new RpcRouter(30);
		router.registerExclusive("test.boom", ctx -> {
			throw new IllegalStateException("echec au milieu de la capture");
		});
		assertEquals("internal", code(router.dispatch("test.boom", new JsonObject())));
		// Sans liberation, ce second appel repondrait 'busy'.
		assertEquals("internal", code(router.dispatch("test.boom", new JsonObject())));
	}

	@Test
	@DisplayName("le catalogue distingue lectures et actions")
	void catalogueKnowsWhichMethodsAreExclusive() {
		RpcRouter router = new RpcRouter(1000);
		router.register("info.status", ctx -> new JsonObject());
		router.registerExclusive("vision.screenshot", ctx -> new JsonObject());
		assertTrue(router.has("info.status"));
		assertFalse(router.isExclusive("info.status"));
		assertTrue(router.isExclusive("vision.screenshot"));
		assertEquals(2, router.methodNames().size());
	}
}
