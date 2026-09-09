package fr.tomda.mcbridge.util;

import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.ThrowingSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** Acces sur aux singletons client et planification sur le thread de rendu. */
public final class ClientMc {
	private ClientMc() {}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Joueur local, ou {@code no_player} s'il n'est pas dans un monde. */
	public static LocalPlayer player() throws RpcException {
		LocalPlayer p = mc().player;
		if (p == null) throw RpcException.noPlayer();
		return p;
	}

	public static ClientLevel level() throws RpcException {
		ClientLevel l = mc().level;
		if (l == null) throw RpcException.noPlayer();
		return l;
	}

	/** Execute la tache sur le thread de rendu et attend son resultat (timeout de la config). */
	public static <T> T call(ThrowingSupplier<T> task) throws RpcException {
		return MainThread.call(mc(), McBridgeMod.config().callTimeoutMs, task);
	}

	public static <T> T call(long timeoutMs, ThrowingSupplier<T> task) throws RpcException {
		return MainThread.call(mc(), timeoutMs, task);
	}
}
