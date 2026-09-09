package fr.tomda.mcbridge.bridge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Passe des threads HTTP au thread principal de Minecraft.
 *
 * <p>{@code Minecraft} est un {@link Executor} : {@code execute} planifie une tache sur le thread
 * de rendu. On bloque le thread HTTP (bon marche) jusqu'a la fin de la tache ou au timeout.
 * Ne jamais appeler {@link #call} depuis le thread principal lui-meme : interblocage garanti.
 */
public final class MainThread {
	private MainThread() {}

	public static <T> T call(Executor gameThread, long timeoutMs, ThrowingSupplier<T> task) throws RpcException {
		CompletableFuture<T> future = new CompletableFuture<>();
		gameThread.execute(() -> {
			try {
				future.complete(task.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		return await(future, timeoutMs);
	}

	/** Attend un future deja planifie (ex. screenshot asynchrone, rechargement de ressources). */
	public static <T> T await(CompletableFuture<T> future, long timeoutMs) throws RpcException {
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new RpcException("timeout", "Le thread de jeu n'a pas termine l'action en " + timeoutMs + " ms.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RpcException("interrupted", "Interrompu en attendant le thread de jeu.");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			if (cause instanceof RpcException rpc) throw rpc;
			throw RpcException.internal(cause);
		}
	}

	/** Planifie sans attendre. */
	public static void run(Executor gameThread, Runnable task) {
		gameThread.execute(task);
	}
}
