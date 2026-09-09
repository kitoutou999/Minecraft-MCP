package fr.tomda.mcbridge.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Attente d'un nombre de ticks client. Sert a laisser le jeu charger les chunks et les modeles
 * apres une teleportation avant de prendre un screenshot, ou a temporiser une action.
 *
 * <p>Compte en fin de tick client ({@code END_CLIENT_TICK}), soit 20 ticks par seconde.
 */
public final class TickWaiter {
	private static final List<Pending> PENDING = new ArrayList<>();
	private static long tick = 0;

	private TickWaiter() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tick++;
			synchronized (PENDING) {
				Iterator<Pending> it = PENDING.iterator();
				while (it.hasNext()) {
					Pending p = it.next();
					if (--p.remaining <= 0) {
						it.remove();
						p.future.complete(null);
					}
				}
			}
		});
	}

	public static long currentTick() {
		return tick;
	}

	/** Future complete apres {@code ticks} ticks (0 ou moins : immediat). Thread-safe. */
	public static CompletableFuture<Void> after(int ticks) {
		CompletableFuture<Void> f = new CompletableFuture<>();
		if (ticks <= 0) {
			f.complete(null);
			return f;
		}
		synchronized (PENDING) {
			PENDING.add(new Pending(ticks, f));
		}
		return f;
	}

	private static final class Pending {
		int remaining;
		final CompletableFuture<Void> future;

		Pending(int remaining, CompletableFuture<Void> future) {
			this.remaining = remaining;
			this.future = future;
		}
	}
}
