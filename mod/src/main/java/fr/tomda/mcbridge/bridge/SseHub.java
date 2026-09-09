package fr.tomda.mcbridge.bridge;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Diffusion des evenements aux abonnes SSE ({@code GET /events}).
 * Chaque abonne a sa file bornee ; un abonne trop lent perd les evenements les plus anciens.
 */
public final class SseHub {
	private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();

	public Subscriber register(Set<String> typeFilter) {
		Subscriber s = new Subscriber(typeFilter);
		subscribers.add(s);
		return s;
	}

	public void unregister(Subscriber s) {
		subscribers.remove(s);
	}

	public int count() {
		return subscribers.size();
	}

	public void broadcast(String type, String json) {
		for (Subscriber s : subscribers) {
			if (s.typeFilter.isEmpty() || s.typeFilter.contains(type)) {
				if (!s.queue.offer(json)) {
					s.queue.poll();
					s.queue.offer(json);
				}
			}
		}
	}

	public static final class Subscriber {
		private final Set<String> typeFilter;
		private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(512);

		private Subscriber(Set<String> typeFilter) {
			this.typeFilter = typeFilter;
		}

		public String poll(long timeoutMs) throws InterruptedException {
			return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
		}
	}
}
