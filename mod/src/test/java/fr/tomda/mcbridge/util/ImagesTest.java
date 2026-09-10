package fr.tomda.mcbridge.util;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traitement des captures : mesure du sujet sur fond uni, comparaison de references, decoupage.
 * Ce sont ces trois calculs qui decident du cadrage renvoye et du verdict d'une regression.
 */
class ImagesTest {

	private static final int BG = 0x202020;

	/** Image unie de la couleur donnee. */
	private static BufferedImage plain(int w, int h, int rgb) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(rgb));
		g.fillRect(0, 0, w, h);
		g.dispose();
		return img;
	}

	private static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", bos);
		return bos.toByteArray();
	}

	/** Fond uni avec un rectangle de sujet pose dessus. */
	private static byte[] subjectOnBackground(int w, int h, int x, int y, int rw, int rh) throws IOException {
		BufferedImage img = plain(w, h, BG);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(0xCC3344));
		g.fillRect(x, y, rw, rh);
		g.dispose();
		return png(img);
	}

	// --- mesure du sujet ---------------------------------------------------------------------

	@Test
	@DisplayName("le rectangle du sujet est mesure au pixel pres")
	void contentBoundsFindsTheSubject() throws IOException {
		int[] bounds = Images.contentBounds(subjectOnBackground(200, 100, 30, 20, 40, 25), BG, 12);
		assertNotNull(bounds);
		assertEquals(30, bounds[0]);
		assertEquals(20, bounds[1]);
		assertEquals(40, bounds[2]);
		assertEquals(25, bounds[3]);
	}

	@Test
	@DisplayName("un fond parfaitement uni ne contient aucun sujet")
	void emptyBackgroundHasNoContent() throws IOException {
		assertNull(Images.contentBounds(png(plain(64, 64, BG)), BG, 12));
	}

	@Test
	@DisplayName("la tolerance absorbe le bruit de compression du fond")
	void toleranceAbsorbsBackgroundNoise() throws IOException {
		// Fond legerement different de la couleur demandee, comme apres un encodage JPEG.
		BufferedImage img = plain(50, 50, 0x232323);
		assertNull(Images.contentBounds(png(img), BG, 12), "un ecart de 3 doit rester du fond");
		assertNotNull(Images.contentBounds(png(img), BG, 1), "sans tolerance, le meme ecart devient un sujet");
	}

	@Test
	@DisplayName("un sujet qui touche un bord est mesure jusqu'au bord")
	void subjectTouchingEdgeIsDetected() throws IOException {
		int[] bounds = Images.contentBounds(subjectOnBackground(80, 60, 0, 0, 80, 10), BG, 12);
		assertNotNull(bounds);
		assertEquals(0, bounds[0]);
		assertEquals(0, bounds[1]);
		assertEquals(80, bounds[2], "le sujet occupe toute la largeur : le cadrage doit reculer");
	}

	// --- comparaison de references -----------------------------------------------------------

	@Test
	@DisplayName("deux captures identiques ne different pas")
	void identicalImagesAreIdentical() throws IOException {
		byte[] shot = subjectOnBackground(120, 90, 10, 10, 30, 30);
		JsonObject d = Images.diff(shot, shot, 8, true);
		assertTrue(d.get("identical").getAsBoolean());
		assertEquals(0, d.get("pixelsDiffering").getAsLong());
		assertEquals(0.0, d.get("percentDiffering").getAsDouble(), 1e-9);
		assertFalse(d.has("diffBase64"), "aucune image de differences quand rien ne bouge");
		assertFalse(d.has("differenceBox"));
	}

	@Test
	@DisplayName("un changement est compte, delimite et illustre")
	void changedRegionIsMeasured() throws IOException {
		byte[] before = png(plain(100, 100, BG));
		byte[] after = subjectOnBackground(100, 100, 20, 30, 10, 10);
		JsonObject d = Images.diff(before, after, 8, true);

		assertFalse(d.get("identical").getAsBoolean());
		assertEquals(100, d.get("pixelsDiffering").getAsLong(), "un carre de 10 sur 10");
		assertEquals(1.0, d.get("percentDiffering").getAsDouble(), 1e-9, "100 pixels sur 10000");
		JsonObject box = d.getAsJsonObject("differenceBox");
		assertEquals(20, box.get("x").getAsInt());
		assertEquals(30, box.get("y").getAsInt());
		assertEquals(10, box.get("width").getAsInt());
		assertEquals(10, box.get("height").getAsInt());
		assertTrue(d.has("diffBase64"), "l'image des differences accompagne un changement");
		assertTrue(Base64.getDecoder().decode(d.get("diffBase64").getAsString()).length > 0);
	}

	@Test
	@DisplayName("la tolerance decide de ce qui compte comme un changement")
	void toleranceDecidesWhatCounts() throws IOException {
		byte[] a = png(plain(40, 40, 0x808080));
		byte[] b = png(plain(40, 40, 0x858585));
		assertTrue(Images.diff(a, b, 8, false).get("identical").getAsBoolean(), "5 de moins que la tolerance 8");
		assertFalse(Images.diff(a, b, 2, false).get("identical").getAsBoolean());
		assertEquals(5, Images.diff(a, b, 8, false).get("maxDelta").getAsInt(),
				"l'ecart reel est rapporte meme quand il est tolere");
	}

	@Test
	@DisplayName("une fenetre redimensionnee est ramenee a la taille de la reference, et signalee")
	void resizedCaptureIsFlagged() throws IOException {
		byte[] reference = png(plain(100, 50, BG));
		byte[] current = png(plain(200, 100, BG));
		JsonObject d = Images.diff(reference, current, 8, false);
		assertTrue(d.get("resized").getAsBoolean());
		assertEquals(100, d.get("width").getAsInt());
		assertEquals(50, d.get("height").getAsInt());
		assertTrue(d.get("identical").getAsBoolean(), "meme contenu a une autre echelle");
	}

	@Test
	@DisplayName("l'image des differences est recadree sur la zone touchee, avec une marge, et situee")
	void diffImageIsCroppedAroundTheChange() throws IOException {
		byte[] before = png(plain(400, 200, BG));
		byte[] after = subjectOnBackground(400, 200, 300, 80, 20, 10);
		JsonObject d = Images.diff(before, after, 8, true, true, 640);

		JsonObject crop = d.getAsJsonObject("diffCrop");
		assertEquals(284, crop.get("x").getAsInt(), "16 pixels de marge a gauche");
		assertEquals(64, crop.get("y").getAsInt(), "16 pixels de marge en haut");
		assertEquals(52, crop.get("width").getAsInt(), "20 de sujet plus deux marges");
		assertEquals(42, crop.get("height").getAsInt());
		assertEquals(52, d.get("diffWidth").getAsInt(), "plus etroit que la limite : pas de reduction");
		assertEquals(42, d.get("diffHeight").getAsInt());
		BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(d.get("diffBase64").getAsString())));
		assertEquals(52, img.getWidth());
		assertEquals(42, img.getHeight());
		assertEquals(0xFF00FF, img.getRGB(16, 16) & 0xFFFFFF, "le changement est en magenta au bord de la marge");
		JsonObject box = d.getAsJsonObject("differenceBox");
		assertEquals(300, box.get("x").getAsInt(), "la zone touchee reste exprimee dans le cadre complet");
	}

	@Test
	@DisplayName("un changement sur toute l'image n'est pas recadre, mais reduit a la largeur demandee")
	void diffImageIsScaledDownWhenEverythingChanged() throws IOException {
		byte[] before = png(plain(200, 100, BG));
		byte[] after = png(plain(200, 100, 0xCC3344));
		JsonObject d = Images.diff(before, after, 8, true, true, 100);

		assertFalse(d.has("diffCrop"), "recadrer sur tout le cadre ne veut rien dire");
		assertEquals(100, d.get("diffWidth").getAsInt());
		assertEquals(50, d.get("diffHeight").getAsInt(), "proportions conservees");
		BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(d.get("diffBase64").getAsString())));
		assertEquals(100, img.getWidth());
		assertEquals(200, d.get("width").getAsInt(), "les mesures restent celles de la reference");
	}

	@Test
	@DisplayName("sans recadrage ni limite, l'image des differences garde la taille de la reference")
	void legacyDiffImageKeepsFullSize() throws IOException {
		byte[] before = png(plain(100, 100, BG));
		byte[] after = subjectOnBackground(100, 100, 20, 30, 10, 10);
		JsonObject d = Images.diff(before, after, 8, true);
		assertFalse(d.has("diffCrop"));
		assertEquals(100, d.get("diffWidth").getAsInt());
		assertEquals(100, d.get("diffHeight").getAsInt());
	}

	// --- decoupage et planche ----------------------------------------------------------------

	@Test
	@DisplayName("le decoupage garde la zone demandee et rappelle la taille d'origine")
	void cropKeepsRequestedRegion() throws IOException {
		JsonObject o = Images.encodeRegion(png(plain(200, 120, BG)), new int[]{10, 20, 50, 40}, 0, 0, "png", 1.0);
		assertEquals(50, o.get("width").getAsInt());
		assertEquals(40, o.get("height").getAsInt());
		assertEquals(200, o.get("sourceWidth").getAsInt());
		assertEquals(120, o.get("sourceHeight").getAsInt());
		JsonObject crop = o.getAsJsonObject("crop");
		assertEquals(10, crop.get("x").getAsInt());
		assertEquals(20, crop.get("y").getAsInt());
	}

	@Test
	@DisplayName("un decoupage qui deborde est ramene dans l'image plutot que de lever")
	void cropOutsideImageIsClamped() throws IOException {
		JsonObject o = Images.encodeRegion(png(plain(60, 60, BG)), new int[]{50, 50, 100, 100}, 0, 0, "png", 1.0);
		assertEquals(10, o.get("width").getAsInt());
		assertEquals(10, o.get("height").getAsInt());
	}

	@Test
	@DisplayName("une case d'inventaire est agrandie d'un facteur entier, jamais reduite en dessous")
	void narrowCropIsUpscaledByWholeFactor() throws IOException {
		// 32 pixels de large, comme une case a l'echelle 2 ; minWidth 256 demande un facteur 8.
		JsonObject o = Images.encodeRegion(png(plain(32, 32, BG)), null, 900, 256, "png", 1.0);
		assertEquals(256, o.get("width").getAsInt());
		assertEquals(256, o.get("height").getAsInt());
	}

	@Test
	@DisplayName("une image plus large que le maximum est reduite en gardant ses proportions")
	void wideImageIsScaledDownKeepingRatio() throws IOException {
		JsonObject o = Images.encodeRegion(png(plain(1920, 1080, BG)), null, 640, 0, "jpeg", 0.85);
		assertEquals(640, o.get("width").getAsInt());
		assertEquals(360, o.get("height").getAsInt());
		assertEquals("jpeg", o.get("format").getAsString());
		assertEquals("image/jpeg", o.get("mimeType").getAsString());
	}

	@Test
	@DisplayName("une image plus petite que le maximum n'est pas agrandie")
	void smallImageIsNotEnlarged() throws IOException {
		JsonObject o = Images.encodeRegion(png(plain(100, 80, BG)), null, 640, 0, "png", 1.0);
		assertEquals(100, o.get("width").getAsInt());
	}

	@Test
	@DisplayName("la planche d'animation range les vues en grille")
	void sheetLaysFramesInAGrid() throws IOException {
		List<byte[]> frames = List.of(png(plain(64, 32, BG)), png(plain(64, 32, BG)),
				png(plain(64, 32, BG)), png(plain(64, 32, BG)));
		JsonObject o = Images.sheet(frames, 2, 64, "png", 1.0);
		assertEquals(2, o.get("columns").getAsInt());
		assertEquals(2, o.get("rows").getAsInt());
		assertEquals(4, o.get("frames").getAsInt());
		assertEquals(64, o.get("cellWidth").getAsInt());
		assertEquals(32, o.get("cellHeight").getAsInt(), "la hauteur suit les proportions de la vue");
		// Deux colonnes de 64 plus un interstice de 2.
		assertEquals(130, o.get("width").getAsInt());
	}

	@Test
	@DisplayName("sans nombre de colonnes, la planche vise le carre")
	void sheetDefaultsToSquareGrid() throws IOException {
		List<byte[]> frames = List.of(png(plain(32, 32, BG)), png(plain(32, 32, BG)),
				png(plain(32, 32, BG)), png(plain(32, 32, BG)), png(plain(32, 32, BG)));
		JsonObject o = Images.sheet(frames, 0, 0, "png", 1.0);
		assertEquals(3, o.get("columns").getAsInt());
		assertEquals(2, o.get("rows").getAsInt());
	}
}
