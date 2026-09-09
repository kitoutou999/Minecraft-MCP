package fr.tomda.mcbridge.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.util.ClientMc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code logs.client} : lecture filtree de {@code logs/latest.log} du client.
 * C'est la que remontent les erreurs de modeles, de textures et de shaders apres un rechargement.
 * Lecture disque uniquement, pas de passage par le thread de rendu.
 */
public final class LogHandlers {
	private LogHandlers() {}

	public static void register(RpcRouter router) {
		router.register("logs.client", ctx -> {
			int lines = Math.max(1, Math.min(ctx.optInt("lines", 100), 2000));
			String file = ctx.optString("file", "latest.log");
			if (file.contains("/") || file.contains("\\") || file.contains("..")) {
				throw RpcException.badRequest("Nom de fichier invalide (pas de chemin).");
			}
			Pattern filter = null;
			String f = ctx.optString("filter", null);
			if (f != null && !f.isBlank()) {
				try {
					filter = Pattern.compile(f, Pattern.CASE_INSENSITIVE);
				} catch (PatternSyntaxException e) {
					throw RpcException.badRequest("Regex 'filter' invalide : " + e.getDescription());
				}
			}
			List<String> levels = new ArrayList<>();
			JsonArray lv = ctx.optArray("levels");
			if (lv != null) lv.forEach(x -> levels.add("/" + x.getAsString().toUpperCase() + "]"));

			Path path = ClientMc.mc().gameDirectory.toPath().resolve("logs").resolve(file);
			if (!Files.exists(path)) throw RpcException.notFound("Fichier de log introuvable : " + path);
			List<String> all;
			try {
				all = Files.readAllLines(path, StandardCharsets.UTF_8);
			} catch (IOException e) {
				all = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
			}
			List<String> kept = new ArrayList<>();
			for (int i = all.size() - 1; i >= 0 && kept.size() < lines; i--) {
				String line = all.get(i);
				if (filter != null && !filter.matcher(line).find()) continue;
				if (!levels.isEmpty() && levels.stream().noneMatch(line::contains)) continue;
				kept.add(line);
			}
			JsonArray arr = new JsonArray();
			for (int i = kept.size() - 1; i >= 0; i--) arr.add(kept.get(i));
			JsonObject o = new JsonObject();
			o.addProperty("file", path.toString());
			o.addProperty("totalLines", all.size());
			o.add("lines", arr);
			return o;
		});
	}
}
