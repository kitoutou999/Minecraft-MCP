package fr.tomda.mcbridge.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcContext;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.focus.FocusState;
import fr.tomda.mcbridge.handlers.VisionHandlers;
import fr.tomda.mcbridge.handlers.WorldHandlers;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.Commands;
import fr.tomda.mcbridge.util.EntityJson;
import fr.tomda.mcbridge.util.Images;
import fr.tomda.mcbridge.util.TickWaiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mode studio : cadrage automatique d'une cible et prise de vue sous un ou plusieurs angles.
 *
 * <p>{@code studio.frameTarget} enchaine, pour chaque angle demande : calcul de la position de
 * camera qui cadre la cible, teleportation par commande, attente, capture. Il sauvegarde et
 * restaure l'etat du focus, le mode de jeu et la position du joueur.
 *
 * <p>{@code studio.bounds} fait le meme calcul sans rien capturer ni deplacer : utile pour verifier
 * un cadrage sans consommer de tokens en images.
 */
public final class StudioHandlers {
	private StudioHandlers() {}

	/** Garde-fou : au-dela, le volume d'images renvoye devient ingerable pour un modele. */
	private static final int MAX_SHOTS = 12;

	/**
	 * Champ de vision applique pendant une prise de vue, et utilise pour calculer la distance.
	 *
	 * <p>Le reglage personnel du joueur peut monter a 110 degres, ce qui deforme fortement le sujet
	 * et oblige a le photographier de tres pres. Un champ etroit donne une perspective naturelle et
	 * un cadrage reproductible d'un poste a l'autre. La valeur du client est restauree ensuite.
	 */
	private static final double STUDIO_FOV = 60.0;

	/** Part de l'image que le sujet doit occuper apres reglage, sur son axe le plus large. */
	private static final double TARGET_FILL = 0.80;
	/** Nombre maximal de corrections de distance, en plus de la prise initiale. */
	private static final int MAX_REFINE_PASSES = 4;

	public static void register(RpcRouter router) {
		router.register("studio.bounds", ctx -> {
			Plan plan = plan(ctx);
			JsonObject o = plan.toJson();
			o.addProperty("captured", false);
			return o;
		});
		router.register("studio.frameTarget", StudioHandlers::frameTarget);
	}

	// --- planification ---------------------------------------------------------------------------

	/** Cadrage calcule : cible, encombrement, distance et liste des positions de camera. */
	private record Shot(StudioAngles angle, Vec3 pos, float yaw, float pitch, double distance) {
		JsonObject toJson() {
			JsonObject o = new JsonObject();
			JsonObject a = new JsonObject();
			a.addProperty("name", angle.name());
			a.addProperty("azimuth", angle.azimuth());
			a.addProperty("pitch", angle.pitch());
			o.add("angle", a);
			JsonObject c = new JsonObject();
			c.add("pos", Json.vec(pos));
			c.addProperty("yaw", yaw);
			c.addProperty("pitch", pitch);
			c.addProperty("distance", distance);
			o.add("camera", c);
			return o;
		}
	}

	private record Plan(JsonObject target, String targetUuid, StudioBounds.Result bounds, double fov,
	                    double aspect, List<Shot> shots) {
		JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.add("target", target);
			o.add("bounds", bounds.toJson());
			o.addProperty("fov", fov);
			o.addProperty("aspect", aspect);
			JsonArray arr = new JsonArray();
			for (Shot s : shots) arr.add(s.toJson());
			o.add("shots", arr);
			return o;
		}
	}

	private static Plan plan(RpcContext ctx) throws Exception {
		double attachRadius = ctx.optDouble("attachRadius", 4.0);
		double margin = ctx.optDouble("margin", 1.15);
		boolean absolute = ctx.optBoolean("absoluteAzimuth", false);
		String boundsSource = ctx.optString("boundsSource", "auto").toLowerCase(Locale.ROOT);
		if (!boundsSource.equals("auto") && !boundsSource.equals("culling") && !boundsSource.equals("hitbox")) {
			throw RpcException.badRequest("boundsSource doit valoir auto, culling ou hitbox.");
		}
		boolean preferCulling = !boundsSource.equals("hitbox");

		// Angles demandes : presets, tourne-disque, ou angles explicites.
		List<StudioAngles> angles = new ArrayList<>();
		JsonArray names = ctx.optArray("angles");
		if (names != null) {
			for (var n : names) {
				StudioAngles a = StudioAngles.preset(n.getAsString());
				if (a == null) {
					throw RpcException.badRequest("Angle inconnu : " + n.getAsString()
							+ ". Valeurs possibles : " + String.join(", ", StudioAngles.presetNames()));
				}
				angles.add(a);
			}
		}
		JsonArray custom = ctx.optArray("customAngles");
		if (custom != null) {
			for (var c : custom) {
				JsonObject o = c.getAsJsonObject();
				double az = o.has("azimuth") ? o.get("azimuth").getAsDouble() : 0;
				double pi = o.has("pitch") ? o.get("pitch").getAsDouble() : 0;
				String nm = o.has("name") ? o.get("name").getAsString()
						: String.format(Locale.ROOT, "custom_%.0f_%.0f", az, pi);
				angles.add(new StudioAngles(nm, az, pi));
			}
		}
		int turntable = ctx.optInt("turntable", 0);
		if (turntable > 0) angles.addAll(StudioAngles.turntable(turntable, ctx.optDouble("turntablePitch", 15)));
		if (angles.isEmpty()) angles.add(StudioAngles.preset("front"));
		if (angles.size() > MAX_SHOTS) {
			throw RpcException.badRequest("Trop de vues demandees (" + angles.size() + ", maximum " + MAX_SHOTS
					+ "). Chaque vue est une image renvoyee au modele.");
		}

		// Resolution de la cible et calcul de l'encombrement, sur le thread de rendu.
		Object[] holder = ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			LocalPlayer player = ClientMc.player();
			Entity target = resolveTarget(ctx, level, player);
			StudioBounds.Result b = StudioBounds.of(target, level, attachRadius, margin, preferCulling);
			return new Object[]{EntityJson.detailed(target, player.position()), target.getStringUUID(), b};
		});
		JsonObject targetJson = (JsonObject) holder[0];
		String targetUuid = (String) holder[1];
		StudioBounds.Result bounds = (StudioBounds.Result) holder[2];

		double fov = ctx.has("fov") ? ctx.getDouble("fov") : STUDIO_FOV;
		fov = Math.max(20, Math.min(110, fov));
		double aspect = ClientMc.call(() -> {
			var w = ClientMc.mc().getWindow();
			return w.getHeight() > 0 ? w.getWidth() / (double) w.getHeight() : 16.0 / 9.0;
		});
		Double fixedDistance = ctx.has("distance") ? ctx.getDouble("distance") : null;

		List<Shot> shots = new ArrayList<>();
		for (StudioAngles a : angles) {
			float yaw = (float) wrapDegrees(absolute ? a.azimuth() : bounds.targetYaw() + 180.0 + a.azimuth());
			float pitch = (float) Math.max(-89.9, Math.min(89.9, a.pitch()));
			double distance = fixedDistance != null ? fixedDistance : fitDistance(bounds, yaw, pitch, fov, aspect, margin);
			distance = Math.max(0.5, Math.min(distance, 256));
			Vec3 look = lookVector(yaw, pitch);
			Vec3 pos = bounds.center().subtract(look.scale(distance));
			shots.add(new Shot(a, pos, yaw, pitch, distance));
		}
		return new Plan(targetJson, targetUuid, bounds, fov, aspect, shots);
	}

	private static Entity resolveTarget(RpcContext ctx, ClientLevel level, LocalPlayer player) throws RpcException {
		if (ctx.has("uuid") || ctx.has("id")) {
			return WorldHandlers.findEntity(level, ctx.optString("uuid", null), ctx.has("id") ? ctx.getInt("id") : null);
		}
		String type = ctx.optString("type", null);
		if (type == null) throw RpcException.badRequest("Fournir 'uuid', 'id' ou 'type'.");
		String wanted = WorldHandlers.normalizeType(type);
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		Vec3 from = player.position();
		for (Entity e : level.entitiesForRendering()) {
			if (!EntityJson.typeId(e).equals(wanted)) continue;
			double d = e.distanceToSqr(from);
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		if (best == null) throw RpcException.notFound("Aucune entite chargee de type " + wanted + ".");
		return best;
	}

	/** Vecteur de visee Minecraft pour un couple (yaw, pitch) en degres. */
	private static Vec3 lookVector(double yawDeg, double pitchDeg) {
		double y = Math.toRadians(yawDeg);
		double p = Math.toRadians(pitchDeg);
		return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/**
	 * Distance minimale pour que toute la boite tienne dans l'image.
	 *
	 * <p>Une sphere englobante suffirait, mais elle gaspille beaucoup de place pour un sujet plat ou
	 * allonge, et ignore le fait qu'un ecran large offre un champ horizontal bien plus grand que
	 * vertical. On projette donc les huit coins de la boite dans le repere de la camera : pour
	 * chaque coin, la contrainte {@code |lateral| <= profondeur * tan(demi-champ)} donne une distance
	 * minimale, et on retient la plus grande. La marge elargit le champ utile, ce qui laisse de
	 * l'air autour du sujet.
	 */
	private static double fitDistance(StudioBounds.Result bounds, double yaw, double pitch,
	                                  double fovDeg, double aspect, double margin) {
		Vec3 look = lookVector(yaw, pitch);
		// Repere ecran : droite horizontale, puis haut par produit vectoriel.
		double yr = Math.toRadians(yaw);
		Vec3 right = new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
		Vec3 up = right.cross(look);
		double m = Math.max(0.5, margin);
		double tanV = Math.tan(Math.toRadians(fovDeg) / 2.0) / m;
		double tanH = tanV * Math.max(0.2, aspect);

		var box = bounds.box();
		Vec3 c = bounds.center();
		double[] xs = {box.minX - c.x, box.maxX - c.x};
		double[] ys = {box.minY - c.y, box.maxY - c.y};
		double[] zs = {box.minZ - c.z, box.maxZ - c.z};
		double needed = 0;
		for (double qx : xs) {
			for (double qy : ys) {
				for (double qz : zs) {
					Vec3 q = new Vec3(qx, qy, qz);
					double depth = q.dot(look);
					needed = Math.max(needed, Math.abs(q.dot(right)) / tanH - depth);
					needed = Math.max(needed, Math.abs(q.dot(up)) / tanV - depth);
				}
			}
		}
		return needed;
	}

	private static double wrapDegrees(double deg) {
		double d = deg % 360.0;
		if (d >= 180.0) d -= 360.0;
		if (d < -180.0) d += 360.0;
		return d;
	}

	// --- prise de vue ----------------------------------------------------------------------------

	private static JsonObject frameTarget(RpcContext ctx) throws Exception {
		BridgeConfig cfg = McBridgeMod.config();
		if (!cfg.enableVision) throw RpcException.unavailable("La vision est desactivee (enableVision=false).");
		if (!cfg.enableCommands) {
			throw RpcException.unavailable("Le cadrage deplace le joueur par commande et enableCommands=false.");
		}
		Minecraft mc = ClientMc.mc();
		Plan plan = plan(ctx);

		boolean studio = ctx.optBoolean("studio", true);
		boolean spectator = ctx.optBoolean("spectator", true);
		boolean returnToStart = ctx.optBoolean("returnToStart", true);
		int waitTicks = Math.max(1, Math.min(ctx.optInt("waitTicks", 6), 200));
		int maxWidth = ctx.optInt("maxWidth", 640);
		String format = ctx.optString("format", "jpeg").toLowerCase(Locale.ROOT);
		double quality = ctx.optDouble("quality", cfg.screenshot.jpegQuality);
		String background = ctx.optString("backgroundColor", "#202020");
		double margin = ctx.optDouble("margin", 1.15);
		boolean refine = studio && ctx.optBoolean("refine", true);
		boolean autoCrop = studio && ctx.optBoolean("autoCrop", true);
		int bgRgb = parseHex(background);

		// Etat a restaurer en fin d'operation.
		FocusState.Snapshot focusBefore = FocusState.INSTANCE.snapshot();
		Object[] before = ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			return new Object[]{p.position(), p.getYRot(), p.getXRot(),
					mc.gameMode != null ? mc.gameMode.getPlayerMode().getSerializedName() : "unknown"};
		});
		Vec3 startPos = (Vec3) before[0];
		float startYaw = (float) before[1];
		float startPitch = (float) before[2];
		String startMode = (String) before[3];
		int startFov = ClientMc.call(() -> ClientMc.mc().options.fov().get());

		// Le cadrage n'est correct que si le rendu utilise bien le champ de vision du calcul.
		int shootFov = (int) Math.round(plan.fov());
		ClientMc.call(() -> {
			ClientMc.mc().options.fov().set(shootFov);
			return null;
		});

		JsonObject spectatorInfo = new JsonObject();
		spectatorInfo.addProperty("requested", spectator);
		spectatorInfo.addProperty("previousMode", startMode);
		boolean modeChanged = false;

		try {
			if (spectator && !"spectator".equals(startMode)) {
				command(cfg.gamemodeCommand + " spectator");
				MainThread.await(TickWaiter.after(4), 4000);
				String now = ClientMc.call(() ->
						mc.gameMode != null ? mc.gameMode.getPlayerMode().getSerializedName() : "unknown");
				if (!"spectator".equals(now)) {
					// Deplacer un joueur en survie vers une position en l'air le ferait chuter.
					throw RpcException.forbidden("Passage en spectateur refuse par le serveur (mode actuel : " + now
							+ "). Donner la permission au joueur, se mettre en spectateur manuellement, ou passer spectator:false.");
				}
				modeChanged = true;
			}
			spectatorInfo.addProperty("applied", modeChanged || "spectator".equals(startMode));

			if (studio) {
				FocusState.INSTANCE.setEntities(Set.of(java.util.UUID.fromString(plan.targetUuid())), Set.of(), Set.of());
				FocusState.INSTANCE.setEntityOptions(ctx.optDouble("attachRadius", 4.0), true, true, true, true, true);
				FocusState.INSTANCE.setScene(true, true, true,
						ctx.optBooleanOrNull("hideBlockEntities") != null ? ctx.optBooleanOrNull("hideBlockEntities") : Boolean.TRUE,
						true, true, true, true, false);
				try {
					FocusState.INSTANCE.setBackgroundColor(background);
				} catch (IllegalArgumentException e) {
					throw RpcException.badRequest(e.getMessage());
				}
			}

			// /tp place les pieds du joueur, la camera est a hauteur des yeux : sans cette correction,
			// le sujet apparait environ 1,6 bloc trop bas dans l'image. Lu apres le passage en
			// spectateur, la posture (et donc la hauteur des yeux) pouvant changer.
			double eyeHeight = ClientMc.call(() -> (double) ClientMc.player().getEyeHeight());

			JsonArray shots = new JsonArray();
			for (Shot shot : plan.shots()) {
				JsonObject entry = shot.toJson();
				double distance = shot.distance();
				byte[] png = shoot(cfg, mc, shot, distance, waitTicks);

				// La taille declaree par un display majore souvent beaucoup le modele affiche : la
				// premiere prise garantit seulement que rien n'est coupe. On mesure ensuite le sujet
				// sur le fond uni et on corrige la distance, en boucle : la relation entre distance et
				// taille apparente n'est proportionnelle que pour un sujet lointain et plat, donc une
				// seule correction depasse souvent la cible. Chaque passe verifie que le sujet ne
				// touche aucun bord avant de se rapprocher davantage.
				if (refine) {
					// Recherche par dichotomie de la distance qui remplit le cadre sans couper le
					// sujet. Une simple correction proportionnelle ne suffit pas : la taille apparente
					// n'evolue en 1/distance que pour un sujet lointain et plat, donc elle depasse la
					// cible de pres. On encadre donc la bonne distance entre une valeur ou le sujet
					// deborde (basse) et une ou il tient entier (haute), et on garde a chaque passe la
					// meilleure image non coupee : celle-ci, jamais la derniere prise, sera renvoyee.
					JsonArray passes = new JsonArray();
					int imgW = fullWidth(mc);
					int imgH = fullHeight(mc);
					double tooClose = 0;
					double known = Double.MAX_VALUE;
					byte[] bestPng = png;
					double bestFill = -1;
					double bestDistance = distance;

					for (int pass = 0; pass <= MAX_REFINE_PASSES; pass++) {
						int[] content = Images.contentBounds(png, bgRgb, 12);
						if (content == null) {
							passes.add(passJson(pass, 0, distance, "aucun sujet visible sur le fond"));
							break;
						}
						boolean clipped = content[0] <= 0 || content[1] <= 0
								|| content[0] + content[2] >= imgW || content[1] + content[3] >= imgH;
						double fill = Math.max(content[2] / (double) imgW, content[3] / (double) imgH);
						if (!clipped && fill > bestFill) {
							bestPng = png;
							bestFill = fill;
							bestDistance = distance;
						}
						if (pass == MAX_REFINE_PASSES) {
							passes.add(passJson(pass, fill, distance, clipped ? "sujet coupe, passe finale" : "cadrage retenu"));
							break;
						}

						double next;
						String reason;
						if (clipped) {
							tooClose = Math.max(tooClose, distance);
							next = known < Double.MAX_VALUE ? (tooClose + known) / 2 : distance * 1.5;
							reason = "sujet coupe, recul";
						} else if (fill < TARGET_FILL * 0.9) {
							known = Math.min(known, distance);
							// Estimation proportionnelle, mais jamais en deca du milieu de l'encadrement :
							// repasser sous une distance deja jugee trop courte ferait boucler la recherche.
							double proportional = distance * fill / TARGET_FILL;
							next = tooClose > 0 ? Math.max(proportional, (tooClose + distance) / 2) : proportional;
							reason = "sujet trop petit, approche";
						} else {
							passes.add(passJson(pass, fill, distance, "cadrage correct"));
							break;
						}
						next = Math.max(0.5, Math.min(256, next));
						passes.add(passJson(pass, fill, next, reason));
						if (Math.abs(next - distance) < 0.05) break;
						distance = next;
						png = shoot(cfg, mc, shot, distance, waitTicks);
					}

					if (bestFill >= 0) {
						png = bestPng;
						distance = bestDistance;
					}
					entry.add("refine", passes);
					entry.addProperty("refineFill", bestFill);
				}

				int[] crop = null;
				if (autoCrop) {
					int[] content = Images.contentBounds(png, bgRgb, 12);
					if (content != null) {
						int pad = (int) Math.round(Math.max(content[2], content[3]) * 0.06) + 2;
						crop = new int[]{content[0] - pad, content[1] - pad, content[2] + pad * 2, content[3] + pad * 2};
					}
				}
				JsonObject image = Images.encodeRegion(png, crop, maxWidth, 0, format, quality);
				entry.addProperty("distanceUsed", distance);
				for (var e : image.entrySet()) entry.add(e.getKey(), e.getValue());
				shots.add(entry);
			}

			JsonObject o = plan.toJson();
			o.add("shots", shots);
			o.addProperty("eyeHeight", eyeHeight);
			o.addProperty("studio", studio);
			o.add("spectator", spectatorInfo);
			o.addProperty("previousFov", startFov);
			o.addProperty("captured", true);
			return o;
		} finally {
			FocusState.INSTANCE.restore(focusBefore);
			try {
				ClientMc.call(() -> {
					ClientMc.mc().options.fov().set(startFov);
					return null;
				});
				if (returnToStart) {
					// La position de depart est celle des pieds : la camera vise 1,62 bloc plus haut.
					Commands.moveCamera(startPos.add(0, ClientMc.call(() -> (double) ClientMc.player().getEyeHeight()), 0),
							startYaw, startPitch);
				}
				if (modeChanged && !"unknown".equals(startMode)) command(cfg.gamemodeCommand + " " + startMode);
			} catch (Exception restoreFailed) {
				McBridgeMod.LOGGER.warn("[mcbridge] restauration apres studio.frameTarget incomplete", restoreFailed);
			}
		}
	}

	private static JsonObject passJson(int index, double fill, double distance, String reason) {
		JsonObject o = new JsonObject();
		o.addProperty("pass", index);
		o.addProperty("fill", fill);
		o.addProperty("distance", distance);
		o.addProperty("reason", reason);
		return o;
	}

	/** Place la camera pour une vue a la distance donnee, verifie l'arrivee, et capture. */
	private static byte[] shoot(BridgeConfig cfg, Minecraft mc, Shot shot, double distance,
	                            int waitTicks) throws Exception {
		Vec3 pos = shot.pos();
		if (Math.abs(distance - shot.distance()) > 1e-6) {
			// Meme direction de visee, distance corrigee.
			Vec3 dir = shot.pos().subtract(centerOf(shot)).normalize();
			pos = centerOf(shot).add(dir.scale(distance));
		}
		Commands.moveCamera(pos, shot.yaw(), shot.pitch());
		byte[] png = VisionHandlers.captureRawPng(cfg, mc, true, true, waitTicks);
		// Un plugin peut rejeter la commande sans que le client le sache : on verifie que le joueur
		// est bien arrive, plutot que de renvoyer une image prise depuis l'ancien point.
		Vec3 target = pos;
		Vec3 actual = ClientMc.call(() -> ClientMc.player().getEyePosition());
		double drift = actual.distanceTo(target);
		if (drift > 1.0) {
			throw new RpcException("teleport_failed", String.format(Locale.ROOT,
					"La camera n'a pas atteint la position demandee (ecart %.2f bloc). La commande '%s' a probablement ete "
							+ "rejetee ou redefinie par un plugin : verifier le chat, les permissions, et le champ "
							+ "teleportCommand de mcbridge.json.", drift, cfg.teleportCommand));
		}
		return png;
	}

	/** Centre vise par une prise de vue, deduit de sa position et de sa distance. */
	private static Vec3 centerOf(Shot shot) {
		return shot.pos().add(lookVector(shot.yaw(), shot.pitch()).scale(shot.distance()));
	}

	private static int fullWidth(Minecraft mc) throws RpcException {
		return ClientMc.call(() -> mc.getWindow().getWidth());
	}

	private static int fullHeight(Minecraft mc) throws RpcException {
		return ClientMc.call(() -> mc.getWindow().getHeight());
	}

	private static int parseHex(String hex) {
		String h = hex.startsWith("#") ? hex.substring(1) : hex;
		try {
			return Integer.parseInt(h, 16);
		} catch (NumberFormatException e) {
			return 0x202020;
		}
	}

	/** Envoie une commande, en respectant le debit maximal ({@link Commands}). */
	private static void command(String command) throws RpcException {
		Commands.send(command);
	}
}
