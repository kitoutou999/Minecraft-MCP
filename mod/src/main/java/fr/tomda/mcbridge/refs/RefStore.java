package fr.tomda.mcbridge.refs;

import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.util.ClientMc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stockage des images de reference sur disque.
 *
 * <p>Chaque reference tient en deux fichiers dans {@code <dossier de jeu>/mcbridge-refs} : l'image
 * PNG et une recette JSON decrivant comment la reproduire (position de camera, champ de vision,
 * options de scene). Sans la recette, une comparaison n'aurait aucun sens : il faut pouvoir
 * reprendre exactement le meme point de vue apres une modification du pack.
 */
public final class RefStore {
	/** Noms de fichiers surs : pas de separateur ni de chemin relatif. */
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private RefStore() {}

	public static Path directory() {
		return ClientMc.mc().gameDirectory.toPath().resolve("mcbridge-refs");
	}

	public static String checkName(String name) throws RpcException {
		if (name == null || !VALID_NAME.matcher(name).matches()) {
			throw RpcException.badRequest("Nom de reference invalide : lettres, chiffres, point, tiret et souligne, "
					+ "64 caracteres au maximum.");
		}
		return name;
	}

	public static Path imagePath(String name) {
		return directory().resolve(name + ".png");
	}

	public static Path recipePath(String name) {
		return directory().resolve(name + ".json");
	}

	public static boolean exists(String name) {
		return Files.exists(imagePath(name)) && Files.exists(recipePath(name));
	}

	public static void save(String name, byte[] png, JsonObject recipe) throws IOException {
		Files.createDirectories(directory());
		Files.write(imagePath(name), png);
		Files.writeString(recipePath(name), Json.PRETTY.toJson(recipe), StandardCharsets.UTF_8);
	}

	public static byte[] loadImage(String name) throws RpcException {
		try {
			return Files.readAllBytes(imagePath(name));
		} catch (IOException e) {
			throw RpcException.notFound("Image de reference introuvable : " + imagePath(name));
		}
	}

	public static JsonObject loadRecipe(String name) throws RpcException {
		try {
			return Json.GSON.fromJson(Files.readString(recipePath(name)), JsonObject.class);
		} catch (Exception e) {
			throw RpcException.notFound("Recette de reference introuvable ou illisible : " + recipePath(name));
		}
	}

	public static boolean delete(String name) throws IOException {
		boolean a = Files.deleteIfExists(imagePath(name));
		boolean b = Files.deleteIfExists(recipePath(name));
		return a || b;
	}

	/** Noms des references presentes, triees. */
	public static List<String> list() throws IOException {
		Path dir = directory();
		if (!Files.isDirectory(dir)) return List.of();
		List<String> names = new ArrayList<>();
		try (var stream = Files.list(dir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".json"))
					.map(p -> p.getFileName().toString().replaceAll("\\.json$", ""))
					.filter(RefStore::exists)
					.forEach(names::add);
		}
		names.sort(Comparator.naturalOrder());
		return names;
	}
}
