package fr.tomda.mcbridge.util;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Format des paquets RCON, isole du reseau pour etre verifiable sans serveur.
 *
 * <p>Un paquet est une longueur, un identifiant de requete et un type, tous entiers 32 bits en
 * petit-boutiste, suivis du corps termine par deux octets nuls. La longueur annoncee couvre tout
 * sauf elle-meme, soit {@code 10 + corps}. Type 3 pour l'authentification, 2 pour une commande,
 * 0 pour une reponse ; une authentification refusee repond avec l'identifiant -1.
 */
public final class RconCodec {
	public static final int TYPE_AUTH = 3;
	public static final int TYPE_COMMAND = 2;
	public static final int TYPE_RESPONSE = 0;
	/** Corps maximal accepte en lecture, garde-fou contre une longueur aberrante. */
	public static final int MAX_BODY = 4096;
	/** Identifiant renvoye par le serveur quand le mot de passe est refuse. */
	public static final int AUTH_FAILED_ID = -1;

	private RconCodec() {}

	public record Packet(int id, int type, String body) {}

	/** Encode un paquet complet, longueur comprise, pret a etre ecrit sur la socket. */
	public static byte[] encode(int id, int type, String body) {
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(10 + payload.length);
		buf.putInt(id);
		buf.putInt(type);
		buf.put(payload);
		buf.put((byte) 0);
		buf.put((byte) 0);
		return buf.array();
	}

	/** Lit un paquet : longueur, puis le bloc annonce. */
	public static Packet read(DataInput in) throws IOException {
		int length = Integer.reverseBytes(in.readInt());
		if (length < 10 || length > MAX_BODY + 12) {
			throw new IOException("Paquet RCON de taille invalide : " + length);
		}
		byte[] data = new byte[length];
		in.readFully(data);
		ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int id = buf.getInt();
		int type = buf.getInt();
		// Le corps occupe le reste, moins les deux octets nuls de fin.
		String body = new String(data, 8, Math.max(0, length - 10), StandardCharsets.UTF_8);
		return new Packet(id, type, body);
	}
}
