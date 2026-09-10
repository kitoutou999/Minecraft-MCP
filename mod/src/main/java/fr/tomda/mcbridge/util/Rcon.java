package fr.tomda.mcbridge.util;

import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.config.BridgeConfig;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Client RCON minimal, sans dependance.
 *
 * <p>RCON execute une commande <b>en tant que console</b> du serveur. Trois consequences par
 * rapport a une commande envoyee en tant que joueur :
 * <ul>
 *   <li>aucun compteur anti-spam, donc pas de deconnexion apres une dizaine d'envois rapproches ;</li>
 *   <li>aucune permission de joueur requise, le compte n'a pas besoin d'etre operateur ;</li>
 *   <li>la sortie de la commande revient, la ou une commande joueur ne laisse qu'un message de chat.</li>
 * </ul>
 *
 * <p>En revanche la console n'a pas de « soi » : les selecteurs relatifs comme {@code @s} ne veulent
 * rien dire, et toute commande doit nommer explicitement sa cible.
 *
 * <p>Le format des paquets est dans {@link RconCodec}, qui ne depend ni du reseau ni du jeu.
 *
 * <p><b>Le protocole n'est pas chiffre</b> : mot de passe et commandes circulent en clair. A
 * reserver a une liaison locale, ou a un tunnel chiffre.
 */
public final class Rcon implements AutoCloseable {
	private static final Object LOCK = new Object();
	private static Rcon shared;
	private static long lastFailureMs = 0;
	/** Apres un echec, ne pas reessayer immediatement a chaque appel. */
	private static final long RETRY_DELAY_MS = 30_000;

	private final Socket socket;
	private final DataInputStream in;
	private final OutputStream out;
	private int requestId = 0;

	private Rcon(String host, int port, String password, int timeoutMs) throws IOException {
		socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), timeoutMs);
		socket.setSoTimeout(timeoutMs);
		in = new DataInputStream(socket.getInputStream());
		out = socket.getOutputStream();
		int id = ++requestId;
		send(id, RconCodec.TYPE_AUTH, password);
		RconCodec.Packet reply = read();
		// Certains serveurs repondent d'abord un paquet vide avant la reponse d'authentification.
		if (reply.type() == RconCodec.TYPE_RESPONSE) reply = read();
		if (reply.id() != id) {
			close();
			throw new IOException("Mot de passe RCON refuse par le serveur.");
		}
	}

	/**
	 * Connexion partagee, ouverte a la demande et rouverte apres une coupure.
	 *
	 * @return null si RCON est desactive, mal configure ou injoignable
	 */
	public static Rcon shared() {
		BridgeConfig.Rcon cfg = McBridgeMod.config().rcon;
		if (cfg == null || !cfg.enabled || cfg.password == null || cfg.password.isBlank()) return null;
		synchronized (LOCK) {
			if (shared != null && shared.isAlive()) return shared;
			if (System.currentTimeMillis() - lastFailureMs < RETRY_DELAY_MS) return null;
			closeQuietly(shared);
			shared = null;
			try {
				shared = new Rcon(cfg.host, cfg.port, cfg.password, cfg.timeoutMs);
				McBridgeMod.LOGGER.info("[mcbridge] RCON connecte sur {}:{}", cfg.host, cfg.port);
				return shared;
			} catch (Exception e) {
				lastFailureMs = System.currentTimeMillis();
				McBridgeMod.LOGGER.warn("[mcbridge] RCON indisponible sur {}:{} ({}), retour aux commandes joueur",
						cfg.host, cfg.port, e.getMessage());
				return null;
			}
		}
	}

	/** Vrai si une connexion RCON est utilisable maintenant. */
	public static boolean available() {
		return shared() != null;
	}

	public static void shutdown() {
		synchronized (LOCK) {
			closeQuietly(shared);
			shared = null;
		}
	}

	/** Execute une commande en tant que console et renvoie sa sortie, eventuellement vide. */
	public String run(String command) throws IOException {
		synchronized (this) {
			int id = ++requestId;
			send(id, RconCodec.TYPE_COMMAND, command);
			StringBuilder body = new StringBuilder();
			// Une reponse longue arrive en plusieurs paquets : un paquet vide envoye ensuite sert de
			// marqueur de fin, le serveur repondant aux requetes dans l'ordre.
			int endId = ++requestId;
			send(endId, RconCodec.TYPE_COMMAND, "");
			while (true) {
				RconCodec.Packet p = read();
				if (p.id() == endId) break;
				body.append(p.body());
			}
			return body.toString();
		}
	}

	/** Execute une commande via la connexion partagee, ou renvoie null si RCON n'est pas disponible. */
	public static String tryRun(String command) {
		Rcon rcon = shared();
		if (rcon == null) return null;
		try {
			return rcon.run(command);
		} catch (IOException e) {
			McBridgeMod.LOGGER.warn("[mcbridge] commande RCON en echec, connexion fermee : {}", e.getMessage());
			shutdown();
			return null;
		}
	}

	private boolean isAlive() {
		return socket != null && socket.isConnected() && !socket.isClosed();
	}

	private void send(int id, int type, String body) throws IOException {
		out.write(RconCodec.encode(id, type, body));
		out.flush();
	}

	private RconCodec.Packet read() throws IOException {
		return RconCodec.read(in);
	}

	@Override
	public void close() {
		closeQuietly(this);
	}

	private static void closeQuietly(Rcon rcon) {
		if (rcon == null) return;
		try {
			rcon.socket.close();
		} catch (IOException ignored) {
			// rien a faire, la connexion sera rouverte au besoin
		}
	}
}
