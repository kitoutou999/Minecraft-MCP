package fr.tomda.mcbridge.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format des paquets RCON : une erreur d'offset donne une sortie tronquee ou illisible. */
class RconCodecTest {

	private static RconCodec.Packet roundTrip(int id, int type, String body) throws IOException {
		byte[] frame = RconCodec.encode(id, type, body);
		return RconCodec.read(new DataInputStream(new ByteArrayInputStream(frame)));
	}

	@Test
	@DisplayName("un paquet encode puis relu redonne l'identifiant, le type et le corps")
	void encodeThenReadKeepsEverything() throws IOException {
		RconCodec.Packet p = roundTrip(7, RconCodec.TYPE_COMMAND, "list");
		assertEquals(7, p.id());
		assertEquals(RconCodec.TYPE_COMMAND, p.type());
		assertEquals("list", p.body());
	}

	@Test
	@DisplayName("la longueur annoncee vaut 10 plus le corps, en petit-boutiste")
	void lengthFieldFollowsTheProtocol() {
		byte[] frame = RconCodec.encode(1, RconCodec.TYPE_AUTH, "secret");
		int announced = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
		assertEquals(10 + "secret".length(), announced);
		assertEquals(4 + announced, frame.length, "la longueur ne se compte pas elle-meme");
		assertEquals(0, frame[frame.length - 1], "le corps se termine par deux octets nuls");
		assertEquals(0, frame[frame.length - 2]);
	}

	@Test
	@DisplayName("un corps vide, un accent ou une longue sortie passent sans perte")
	void bodiesOfEveryShapeSurvive() throws IOException {
		assertEquals("", roundTrip(2, RconCodec.TYPE_COMMAND, "").body());
		assertEquals("deja la", roundTrip(3, RconCodec.TYPE_RESPONSE, "deja la").body());
		String accented = "joueur teleporte a cote de l'ilot";
		assertEquals(accented, roundTrip(4, RconCodec.TYPE_RESPONSE, accented).body());
		String long_ = "x".repeat(RconCodec.MAX_BODY - 100);
		assertEquals(long_, roundTrip(5, RconCodec.TYPE_RESPONSE, long_).body());
	}

	@Test
	@DisplayName("un corps multi-octets n'est pas coupe au milieu d'un caractere")
	void multiByteBodyIsNotTruncated() throws IOException {
		String body = "eee accents et fleche ->";
		RconCodec.Packet p = roundTrip(6, RconCodec.TYPE_RESPONSE, body);
		assertEquals(body, p.body());
	}

	@Test
	@DisplayName("une longueur aberrante est refusee plutot que d'allouer n'importe quoi")
	void absurdLengthIsRejected() {
		byte[] tooShort = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).array();
		assertThrows(IOException.class,
				() -> RconCodec.read(new DataInputStream(new ByteArrayInputStream(tooShort))));

		byte[] tooLong = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Integer.MAX_VALUE).array();
		assertThrows(IOException.class,
				() -> RconCodec.read(new DataInputStream(new ByteArrayInputStream(tooLong))));
	}

	@Test
	@DisplayName("deux paquets a la suite se lisent l'un apres l'autre")
	void consecutivePacketsAreReadInOrder() throws IOException {
		byte[] first = RconCodec.encode(1, RconCodec.TYPE_RESPONSE, "debut");
		byte[] second = RconCodec.encode(2, RconCodec.TYPE_RESPONSE, "fin");
		byte[] both = new byte[first.length + second.length];
		System.arraycopy(first, 0, both, 0, first.length);
		System.arraycopy(second, 0, both, first.length, second.length);

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(both));
		assertEquals("debut", RconCodec.read(in).body());
		RconCodec.Packet p = RconCodec.read(in);
		assertEquals(2, p.id());
		assertEquals("fin", p.body());
		assertTrue(in.available() == 0);
	}
}
