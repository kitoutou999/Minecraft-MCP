package fr.tomda.mcbridge.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.util.EntityJson;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Calcul de l'encombrement visuel d'une cible, pour cadrer la camera.
 *
 * <p>Probleme : la boite englobante d'un {@code item_display} est un point (min = max), alors que
 * le modele affiche peut faire plusieurs blocs. Un mob ModelEngine est une entite de base plus un
 * {@code item_display} par os, un meuble Nexo un {@code item_display} plus une {@code interaction}.
 *
 * <p>Solution : pour chaque partie, prendre la boite de visibilite
 * ({@code Entity.getBoundingBoxForCulling()}) quand elle est exploitable, sinon la hitbox. Un
 * display porte dans cette boite la taille que le plugin a declaree pour que le modele ne soit
 * jamais coupe par le culling : un meuble Nexo dont la hitbox est un point annonce ainsi 4 blocs
 * sur 2. C'est une majoration, souvent plus large que le modele reel, mais c'est la seule mesure
 * disponible cote client, et mieux vaut cadrer trop large que couper le sujet. Le parametre de
 * marge permet de resserrer.
 */
public final class StudioBounds {
	private StudioBounds() {}

	/** En deca, une dimension est consideree comme nulle (boite d'un display reduite a un point). */
	private static final double DEGENERATE = 0.05;
	/** Taille attribuee a une dimension nulle : un item display affiche environ un bloc. */
	private static final double DEFAULT_SIZE = 1.0;
	/** Au-dela, une boite de visibilite est jugee aberrante et la hitbox reprend la main. */
	private static final double MAX_SANE_SIZE = 48.0;

	/** Une boite de visibilite n'est retenue que si elle est ni degeneree ni aberrante. */
	private static boolean usable(AABB b) {
		return b.getXsize() >= DEGENERATE && b.getYsize() >= DEGENERATE && b.getZsize() >= DEGENERATE
				&& b.getXsize() <= MAX_SANE_SIZE && b.getYsize() <= MAX_SANE_SIZE && b.getZsize() <= MAX_SANE_SIZE;
	}

	/** D'ou vient la boite retenue pour une partie. */
	public enum Source {
		/** Boite de visibilite : taille declaree pour le rendu, disponible sur les displays. */
		CULLING,
		/** Hitbox de collision. */
		HITBOX
	}

	public record Result(AABB box, Vec3 center, double radius, float targetYaw, List<Entity> parts,
	                     List<Source> sources) {
		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			JsonObject b = new JsonObject();
			b.add("min", Json.vec(box.minX, box.minY, box.minZ));
			b.add("max", Json.vec(box.maxX, box.maxY, box.maxZ));
			b.add("size", Json.vec(box.getXsize(), box.getYsize(), box.getZsize()));
			o.add("box", b);
			o.add("center", Json.vec(center));
			o.addProperty("radius", radius);
			o.addProperty("targetYaw", targetYaw);
			JsonArray arr = new JsonArray();
			for (int i = 0; i < parts.size(); i++) {
				JsonObject p = EntityJson.summary(parts.get(i), center);
				p.addProperty("boundsSource", sources.get(i).name().toLowerCase(java.util.Locale.ROOT));
				arr.add(p);
			}
			o.add("parts", arr);
			return o;
		}
	}

	/**
	 * @param target       entite principale
	 * @param level        monde client
	 * @param attachRadius rayon de recherche des displays attaches (0 = aucun)
	 * @param margin       marge multiplicative appliquee au rayon (1.0 = ajuste au plus juste)
	 * @param preferCulling utiliser la boite de visibilite quand elle est exploitable
	 */
	public static Result of(Entity target, ClientLevel level, double attachRadius, double margin, boolean preferCulling) {
		Set<Entity> parts = new LinkedHashSet<>();
		parts.add(target);
		parts.addAll(target.getPassengers());
		if (target.getVehicle() != null) parts.add(target.getVehicle());
		if (attachRadius > 0) {
			double r2 = attachRadius * attachRadius;
			Vec3 c = target.position();
			for (Entity e : level.entitiesForRendering()) {
				if (e == target || !EntityJson.isDisplay(e)) continue;
				if (e.distanceToSqr(c) <= r2) parts.add(e);
			}
		}

		AABB box = null;
		List<Source> sources = new ArrayList<>();
		for (Entity e : parts) {
			AABB b = e.getBoundingBox();
			Source src = Source.HITBOX;
			if (preferCulling && e instanceof Display display) {
				AABB culling = display.getBoundingBoxForCulling();
				if (culling != null && usable(culling)) {
					b = culling;
					src = Source.CULLING;
				}
			}
			sources.add(src);
			box = box == null ? b : box.minmax(b);
		}
		if (box == null) box = target.getBoundingBox();

		// Ne gonfler que les dimensions reellement nulles, sans deplacer le centre. Une dimension
		// simplement petite (un tapis, une dalle) est reelle : la gonfler cadrerait trop large.
		double gx = box.getXsize() < DEGENERATE ? DEFAULT_SIZE / 2 : 0;
		double gy = box.getYsize() < DEGENERATE ? DEFAULT_SIZE / 2 : 0;
		double gz = box.getZsize() < DEGENERATE ? DEFAULT_SIZE / 2 : 0;
		if (gx > 0 || gy > 0 || gz > 0) box = box.inflate(gx, gy, gz);

		Vec3 center = box.getCenter();
		double radius = 0.5 * Math.sqrt(box.getXsize() * box.getXsize()
				+ box.getYsize() * box.getYsize()
				+ box.getZsize() * box.getZsize()) * Math.max(0.5, margin);
		return new Result(box, center, radius, target.getYRot(), new ArrayList<>(parts), sources);
	}
}
