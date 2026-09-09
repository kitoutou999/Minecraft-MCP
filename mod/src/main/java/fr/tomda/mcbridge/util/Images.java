package fr.tomda.mcbridge.util;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * Encodage des captures : NativeImage vers PNG/JPEG redimensionne, en base64.
 *
 * <p>Le passage par un fichier temporaire est volontaire : {@code NativeImage.writeToFile} est
 * l'API stable pour obtenir un PNG correct quelle que soit la disposition memoire interne.
 * Tout le travail lourd (decodage, redimensionnement, encodage) se fait hors du thread de rendu.
 */
public final class Images {
	private Images() {}

	/** Lit une NativeImage en octets PNG. A appeler sur le thread qui possede l'image. */
	public static byte[] toPng(NativeImage image) throws IOException {
		Path tmp = Files.createTempFile("mcbridge_shot", ".png");
		try {
			image.writeToFile(tmp);
			return Files.readAllBytes(tmp);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Convertit des octets PNG en image finale.
	 *
	 * @param png      donnees PNG source
	 * @param maxWidth largeur max (0 = pas de redimensionnement) ; l'image n'est jamais agrandie
	 * @param format   "png" ou "jpeg"
	 * @param quality  qualite JPEG entre 0 et 1
	 * @return objet {@code {format, mimeType, width, height, bytes, base64}}
	 */
	public static JsonObject encode(byte[] png, int maxWidth, String format, double quality) throws IOException {
		return encodeRegion(png, null, maxWidth, 0, format, quality);
	}

	/**
	 * Decoupe puis redimensionne une capture.
	 *
	 * @param crop        zone a garder en pixels physiques {@code {x, y, largeur, hauteur}}, ou null
	 * @param maxWidth    largeur maximale du resultat (0 = illimitee)
	 * @param minWidth    largeur minimale : un decoupage etroit (une case d'inventaire fait 32
	 *                    pixels) est agrandi pour rester lisible
	 *
	 * <p>L'agrandissement utilise le plus proche voisin, qui conserve les aretes nettes des
	 * textures de Minecraft, la reduction une interpolation bilineaire.
	 */
	public static JsonObject encodeRegion(byte[] png, int[] crop, int maxWidth, int minWidth,
	                                      String format, double quality) throws IOException {
		BufferedImage full = ImageIO.read(new java.io.ByteArrayInputStream(png));
		if (full == null) throw new IOException("PNG source illisible");
		boolean jpeg = "jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format);

		BufferedImage src = full;
		int[] applied = null;
		if (crop != null) {
			int cx = Math.max(0, Math.min(crop[0], full.getWidth() - 1));
			int cy = Math.max(0, Math.min(crop[1], full.getHeight() - 1));
			int cw = Math.max(1, Math.min(crop[2], full.getWidth() - cx));
			int ch = Math.max(1, Math.min(crop[3], full.getHeight() - cy));
			src = full.getSubimage(cx, cy, cw, ch);
			applied = new int[]{cx, cy, cw, ch};
		}

		int w = src.getWidth();
		int h = src.getHeight();
		if (minWidth > 0 && w < minWidth) {
			// Facteur entier : un agrandissement fractionnaire brouillerait les pixels des textures.
			int factor = Math.max(1, (int) Math.ceil(minWidth / (double) w));
			w *= factor;
			h *= factor;
		}
		if (maxWidth > 0 && w > maxWidth) {
			h = Math.max(1, (int) Math.round(h * (maxWidth / (double) w)));
			w = maxWidth;
		}

		BufferedImage out;
		if (w == src.getWidth() && h == src.getHeight()) {
			out = toType(src, jpeg);
		} else {
			out = new BufferedImage(w, h, jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = out.createGraphics();
			boolean upscale = w > src.getWidth();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					upscale ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, w, h, null);
			g.dispose();
		}

		byte[] bytes = jpeg ? writeJpeg(out, quality) : writePng(out);
		JsonObject o = new JsonObject();
		o.addProperty("format", jpeg ? "jpeg" : "png");
		o.addProperty("mimeType", jpeg ? "image/jpeg" : "image/png");
		o.addProperty("width", w);
		o.addProperty("height", h);
		o.addProperty("sourceWidth", full.getWidth());
		o.addProperty("sourceHeight", full.getHeight());
		if (applied != null) {
			JsonObject c = new JsonObject();
			c.addProperty("x", applied[0]);
			c.addProperty("y", applied[1]);
			c.addProperty("width", applied[2]);
			c.addProperty("height", applied[3]);
			o.add("crop", c);
		}
		o.addProperty("bytes", bytes.length);
		o.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
		return o;
	}

	/**
	 * Rectangle occupe par le sujet sur un fond uni.
	 *
	 * <p>Sur une capture studio (terrain, ciel et particules masques), tout ce qui n'est pas la
	 * couleur de fond est le sujet. Mesurer directement l'image evite de dependre des dimensions
	 * declarees par les entites, qui majorent souvent beaucoup le modele reellement affiche.
	 *
	 * @param bgRgb     couleur de fond attendue, en 0xRRGGBB
	 * @param tolerance ecart tolere par canal
	 * @return {@code {x, y, largeur, hauteur}} en pixels, ou null si l'image est entierement du fond
	 */
	public static int[] contentBounds(byte[] png, int bgRgb, int tolerance) throws IOException {
		BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
		if (img == null) throw new IOException("PNG source illisible");
		int bgR = (bgRgb >> 16) & 0xFF;
		int bgG = (bgRgb >> 8) & 0xFF;
		int bgB = bgRgb & 0xFF;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
		int w = img.getWidth();
		int h = img.getHeight();
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			img.getRGB(0, y, w, 1, row, 0, w);
			for (int x = 0; x < w; x++) {
				int rgb = row[x];
				if (Math.abs(((rgb >> 16) & 0xFF) - bgR) <= tolerance
						&& Math.abs(((rgb >> 8) & 0xFF) - bgG) <= tolerance
						&& Math.abs((rgb & 0xFF) - bgB) <= tolerance) {
					continue;
				}
				if (x < minX) minX = x;
				if (x > maxX) maxX = x;
				if (y < minY) minY = y;
				if (y > maxY) maxY = y;
			}
		}
		if (maxX < 0) return null;
		return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
	}

	/**
	 * Assemble plusieurs captures en une planche unique, lue de gauche a droite puis de haut en bas.
	 *
	 * <p>Une animation renvoyee image par image coute tres cher a un modele ; une planche unique
	 * montre le mouvement d'un coup pour le prix d'une seule image.
	 *
	 * @param columns   nombre de colonnes (0 = grille la plus carree possible)
	 * @param cellWidth largeur de chaque vignette (0 = taille d'origine)
	 */
	public static JsonObject sheet(List<byte[]> pngs, int columns, int cellWidth, String format, double quality) throws IOException {
		if (pngs.isEmpty()) throw new IOException("Aucune image a assembler");
		List<BufferedImage> frames = new ArrayList<>();
		for (byte[] png : pngs) {
			BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
			if (img == null) throw new IOException("PNG illisible dans la serie");
			frames.add(img);
		}
		int n = frames.size();
		int cols = columns > 0 ? columns : (int) Math.ceil(Math.sqrt(n));
		int rows = (int) Math.ceil(n / (double) cols);
		BufferedImage first = frames.get(0);
		int cw = cellWidth > 0 ? cellWidth : first.getWidth();
		int ch = Math.max(1, (int) Math.round(first.getHeight() * (cw / (double) first.getWidth())));
		int gap = 2;
		boolean jpeg = "jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format);

		BufferedImage out = new BufferedImage(cols * cw + (cols - 1) * gap, rows * ch + (rows - 1) * gap,
				jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new java.awt.Color(0x20, 0x20, 0x20));
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int i = 0; i < n; i++) {
			int x = (i % cols) * (cw + gap);
			int y = (i / cols) * (ch + gap);
			g.drawImage(frames.get(i), x, y, cw, ch, null);
		}
		g.dispose();

		byte[] bytes = jpeg ? writeJpeg(out, quality) : writePng(out);
		JsonObject o = new JsonObject();
		o.addProperty("format", jpeg ? "jpeg" : "png");
		o.addProperty("mimeType", jpeg ? "image/jpeg" : "image/png");
		o.addProperty("width", out.getWidth());
		o.addProperty("height", out.getHeight());
		o.addProperty("columns", cols);
		o.addProperty("rows", rows);
		o.addProperty("frames", n);
		o.addProperty("cellWidth", cw);
		o.addProperty("cellHeight", ch);
		o.addProperty("bytes", bytes.length);
		o.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
		return o;
	}

	/**
	 * Compare deux captures pixel a pixel.
	 *
	 * <p>Si les tailles different (fenetre redimensionnee entre les deux), la seconde est ramenee a
	 * la taille de la premiere et le resultat le signale : de petits ecarts peuvent alors venir du
	 * redimensionnement et non d'un vrai changement.
	 *
	 * @param tolerance ecart tolere par canal avant de compter un pixel comme different
	 * @param diffImage produire une image ou les pixels differents sont surlignes
	 */
	public static JsonObject diff(byte[] referencePng, byte[] currentPng, int tolerance, boolean diffImage) throws IOException {
		BufferedImage ref = ImageIO.read(new java.io.ByteArrayInputStream(referencePng));
		BufferedImage cur = ImageIO.read(new java.io.ByteArrayInputStream(currentPng));
		if (ref == null || cur == null) throw new IOException("PNG illisible pour la comparaison");
		int w = ref.getWidth();
		int h = ref.getHeight();
		boolean resized = cur.getWidth() != w || cur.getHeight() != h;
		if (resized) {
			BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(cur, 0, 0, w, h, null);
			g.dispose();
			cur = scaled;
		}

		long differing = 0;
		long deltaSum = 0;
		int maxDelta = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
		BufferedImage out = diffImage ? new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB) : null;
		int[] rowRef = new int[w];
		int[] rowCur = new int[w];
		for (int y = 0; y < h; y++) {
			ref.getRGB(0, y, w, 1, rowRef, 0, w);
			cur.getRGB(0, y, w, 1, rowCur, 0, w);
			for (int x = 0; x < w; x++) {
				int a = rowRef[x];
				int b = rowCur[x];
				int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
				int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
				int db = Math.abs((a & 0xFF) - (b & 0xFF));
				int d = Math.max(dr, Math.max(dg, db));
				if (d > maxDelta) maxDelta = d;
				if (d > tolerance) {
					differing++;
					deltaSum += d;
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
					if (out != null) out.setRGB(x, y, 0xFF00FF);
				} else if (out != null) {
					// Zones identiques en gris attenue, pour faire ressortir les differences.
					int grey = (((b >> 16) & 0xFF) * 30 + ((b >> 8) & 0xFF) * 59 + (b & 0xFF) * 11) / 100 / 3 + 20;
					out.setRGB(x, y, (grey << 16) | (grey << 8) | grey);
				}
			}
		}

		long total = (long) w * h;
		JsonObject o = new JsonObject();
		o.addProperty("width", w);
		o.addProperty("height", h);
		o.addProperty("resized", resized);
		o.addProperty("tolerance", tolerance);
		o.addProperty("pixelsTotal", total);
		o.addProperty("pixelsDiffering", differing);
		o.addProperty("percentDiffering", total == 0 ? 0 : differing * 100.0 / total);
		o.addProperty("maxDelta", maxDelta);
		o.addProperty("meanDeltaOnDiffering", differing == 0 ? 0 : deltaSum / (double) differing);
		o.addProperty("identical", differing == 0);
		if (maxX >= 0) {
			JsonObject box = new JsonObject();
			box.addProperty("x", minX);
			box.addProperty("y", minY);
			box.addProperty("width", maxX - minX + 1);
			box.addProperty("height", maxY - minY + 1);
			o.add("differenceBox", box);
		}
		if (out != null && differing > 0) {
			byte[] bytes = writePng(out);
			o.addProperty("diffFormat", "png");
			o.addProperty("diffMimeType", "image/png");
			o.addProperty("diffBytes", bytes.length);
			o.addProperty("diffBase64", Base64.getEncoder().encodeToString(bytes));
		}
		return o;
	}

	private static BufferedImage toType(BufferedImage src, boolean jpeg) {
		int type = jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
		if (src.getType() == type) return src;
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), type);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}

	private static byte[] writePng(BufferedImage img) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", bos);
		return bos.toByteArray();
	}

	private static byte[] writeJpeg(BufferedImage img, double quality) throws IOException {
		Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
		if (!it.hasNext()) throw new IOException("Aucun encodeur JPEG disponible");
		ImageWriter writer = it.next();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
			writer.setOutput(ios);
			ImageWriteParam p = writer.getDefaultWriteParam();
			p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			p.setCompressionQuality((float) Math.max(0.05, Math.min(1.0, quality)));
			writer.write(null, new IIOImage(img, null, null), p);
		} finally {
			writer.dispose();
		}
		return bos.toByteArray();
	}
}
