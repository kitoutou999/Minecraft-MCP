package fr.tomda.mcbridge.focus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.bridge.Json;
import fr.tomda.mcbridge.util.EntityJson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Etat du mode focus, consulte par les mixins de rendu. Trois volets independants et cumulables :
 *
 * <p><b>Selection d'entites</b> (uuids, ids, types) : seules les entites selectionnees sont rendues,
 * plus leurs passagers et les entites display proches (os ModelEngine, parties Nexo). Regles dans
 * {@link #shouldRender}.
 *
 * <p><b>Region</b> : boite de blocs (bornes inclusives). Tout bloc hors de la boite est vu comme de
 * l'air par les compilateurs de sections (vanilla {@code RenderSectionRegion}, Sodium
 * {@code LevelSlice}), et les entites hors de la boite sont masquees. Changer la region impose une
 * reconstruction des sections ({@code LevelRenderer.allChanged()}), faite par {@code FocusHandlers}.
 *
 * <p><b>Scene</b> : masquage du terrain, du ciel (avec nuages et meteo), des particules, des block
 * entities ; suppression du brouillard, du clipping camera en 3e personne et de l'overlay "camera
 * dans un bloc" ; couleur de fond unie (remplace la couleur de brouillard utilisee pour effacer
 * l'ecran, visible des que ciel et terrain sont masques).
 *
 * <p>Tous les champs sont volatils : ecrits depuis les threads HTTP, lus depuis le thread de rendu
 * et les threads de compilation de sections. Les mixins ne contiennent aucune logique, ils lisent
 * cet etat. Points d'accroche verifies sur Minecraft 26.1.2, voir docs/ROADMAP.md.
 */
public final class FocusState {
	public static final FocusState INSTANCE = new FocusState();

	// --- selection d'entites -------------------------------------------------------------------
	private volatile Set<UUID> uuids = Set.of();
	private volatile Set<Integer> ids = Set.of();
	private volatile Set<String> types = Set.of();
	private volatile double attachRadius = 4.0;
	private volatile boolean includePassengers = true;
	private volatile boolean includeAttachedDisplays = true;
	private volatile boolean hideOthers = true;
	private volatile boolean hideSelf = true;
	private volatile boolean hideCameraOccluders = true;

	// --- region ----------------------------------------------------------------------------------
	/** Boite en coordonnees monde, [min, max+1) sur chaque axe ; null = pas de region. */
	private volatile AABB region = null;
	private volatile boolean hideEntitiesOutsideRegion = true;

	// --- scene -----------------------------------------------------------------------------------
	private volatile boolean hideTerrain = false;
	private volatile boolean hideSky = false;
	private volatile boolean hideParticles = false;
	private volatile boolean hideBlockEntities = false;
	private volatile boolean disableFog = false;
	private volatile boolean disableCameraClipping = false;
	private volatile boolean hideInsideBlockOverlay = false;
	private volatile boolean flatLighting = false;
	/** Masque toutes les entites, y compris celles selectionnees : pour photographier un decor seul. */
	private volatile boolean hideAllEntities = false;
	/** Couleur de fond RGBA (0..1) ; null = couleur de brouillard vanilla. */
	private volatile Vector4f backgroundColor = null;

	// cache par tick des entites racines selectionnees (thread de rendu uniquement)
	private long cacheTick = -1;
	private List<Entity> roots = List.of();
	private Set<Integer> rootIds = Set.of();

	private FocusState() {}

	// --- lecture (mixins) --------------------------------------------------------------------------

	public boolean hasEntitySelection() {
		return !uuids.isEmpty() || !ids.isEmpty() || !types.isEmpty();
	}

	public boolean isRegionActive() {
		return region != null;
	}

	/** Vrai si au moins un volet est actif (pour info.status). */
	public boolean isActive() {
		return hasEntitySelection() || region != null || hideTerrain || hideSky || hideParticles || hideBlockEntities
				|| disableFog || disableCameraClipping || hideInsideBlockOverlay || flatLighting || hideAllEntities
				|| backgroundColor != null;
	}

	public boolean hideTerrain() {
		return hideTerrain;
	}

	public boolean hideSky() {
		return hideSky;
	}

	public boolean hideParticles() {
		return hideParticles;
	}

	public boolean hideBlockEntities() {
		return hideBlockEntities;
	}

	public boolean disableFog() {
		return disableFog;
	}

	public boolean disableCameraClipping() {
		return disableCameraClipping;
	}

	public boolean hideInsideBlockOverlay() {
		return hideInsideBlockOverlay;
	}

	public boolean flatLighting() {
		return flatLighting;
	}

	public boolean hideAllEntities() {
		return hideAllEntities;
	}

	public Vector4f backgroundColor() {
		return backgroundColor;
	}

	/** Un bloc hors de la region active est rendu comme de l'air. */
	public boolean isBlockVisible(int x, int y, int z) {
		AABB r = region;
		if (r == null) return true;
		return x >= r.minX && x < r.maxX && y >= r.minY && y < r.maxY && z >= r.minZ && z < r.maxZ;
	}

	// --- ecriture (handlers) -----------------------------------------------------------------------

	public synchronized void setEntities(Set<UUID> uuids, Set<Integer> ids, Set<String> types) {
		this.uuids = Set.copyOf(uuids);
		this.ids = Set.copyOf(ids);
		this.types = Set.copyOf(types);
		this.cacheTick = -1;
	}

	public synchronized void setEntityOptions(Double attachRadius, Boolean includePassengers, Boolean includeAttachedDisplays,
	                                          Boolean hideOthers, Boolean hideSelf, Boolean hideCameraOccluders) {
		if (attachRadius != null) this.attachRadius = attachRadius;
		if (includePassengers != null) this.includePassengers = includePassengers;
		if (includeAttachedDisplays != null) this.includeAttachedDisplays = includeAttachedDisplays;
		if (hideOthers != null) this.hideOthers = hideOthers;
		if (hideSelf != null) this.hideSelf = hideSelf;
		if (hideCameraOccluders != null) this.hideCameraOccluders = hideCameraOccluders;
	}

	public synchronized void setScene(Boolean hideTerrain, Boolean hideSky, Boolean hideParticles, Boolean hideBlockEntities,
	                                  Boolean disableFog, Boolean disableCameraClipping, Boolean hideInsideBlockOverlay,
	                                  Boolean flatLighting, Boolean hideAllEntities) {
		if (hideTerrain != null) this.hideTerrain = hideTerrain;
		if (hideSky != null) this.hideSky = hideSky;
		if (hideParticles != null) this.hideParticles = hideParticles;
		if (hideBlockEntities != null) this.hideBlockEntities = hideBlockEntities;
		if (disableFog != null) this.disableFog = disableFog;
		if (disableCameraClipping != null) this.disableCameraClipping = disableCameraClipping;
		if (hideInsideBlockOverlay != null) this.hideInsideBlockOverlay = hideInsideBlockOverlay;
		if (flatLighting != null) this.flatLighting = flatLighting;
		if (hideAllEntities != null) this.hideAllEntities = hideAllEntities;
	}

	/** Couleur "#RRGGBB" ou "RRGGBB" ; null ou "none" retire la couleur de fond. */
	public synchronized void setBackgroundColor(String hex) {
		if (hex == null || hex.isBlank() || hex.equalsIgnoreCase("none")) {
			this.backgroundColor = null;
			return;
		}
		String h = hex.startsWith("#") ? hex.substring(1) : hex;
		if (h.length() != 6) throw new IllegalArgumentException("Couleur attendue au format #RRGGBB : " + hex);
		int rgb = Integer.parseInt(h, 16);
		this.backgroundColor = new Vector4f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
	}

	/** Region par bornes de blocs inclusives (l'ordre des coins est libre). */
	public synchronized void setRegion(int x1, int y1, int z1, int x2, int y2, int z2, boolean hideEntitiesOutside) {
		this.region = new AABB(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
				Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1);
		this.hideEntitiesOutsideRegion = hideEntitiesOutside;
	}

	public synchronized void clearRegion() {
		this.region = null;
	}

	public synchronized void clear() {
		uuids = Set.of();
		ids = Set.of();
		types = Set.of();
		attachRadius = 4.0;
		includePassengers = true;
		includeAttachedDisplays = true;
		hideOthers = true;
		hideSelf = true;
		hideCameraOccluders = true;
		region = null;
		hideEntitiesOutsideRegion = true;
		hideTerrain = hideSky = hideParticles = hideBlockEntities = false;
		disableFog = disableCameraClipping = hideInsideBlockOverlay = flatLighting = hideAllEntities = false;
		backgroundColor = null;
		roots = List.of();
		rootIds = Set.of();
		cacheTick = -1;
	}

	// --- sauvegarde et restauration (utilisees par le mode studio) ---------------------------------

	/** Copie complete de l'etat, pour restauration apres une operation temporaire. */
	public record Snapshot(Set<UUID> uuids, Set<Integer> ids, Set<String> types, double attachRadius,
	                       boolean includePassengers, boolean includeAttachedDisplays, boolean hideOthers,
	                       boolean hideSelf, boolean hideCameraOccluders, AABB region, boolean hideEntitiesOutsideRegion,
	                       boolean hideTerrain, boolean hideSky, boolean hideParticles, boolean hideBlockEntities,
	                       boolean disableFog, boolean disableCameraClipping, boolean hideInsideBlockOverlay,
	                       boolean flatLighting, boolean hideAllEntities, Vector4f backgroundColor) {}

	public synchronized Snapshot snapshot() {
		Vector4f bg = backgroundColor;
		return new Snapshot(uuids, ids, types, attachRadius, includePassengers, includeAttachedDisplays, hideOthers,
				hideSelf, hideCameraOccluders, region, hideEntitiesOutsideRegion, hideTerrain, hideSky, hideParticles,
				hideBlockEntities, disableFog, disableCameraClipping, hideInsideBlockOverlay, flatLighting,
				hideAllEntities, bg == null ? null : new Vector4f(bg));
	}

	public synchronized void restore(Snapshot s) {
		uuids = s.uuids();
		ids = s.ids();
		types = s.types();
		attachRadius = s.attachRadius();
		includePassengers = s.includePassengers();
		includeAttachedDisplays = s.includeAttachedDisplays();
		hideOthers = s.hideOthers();
		hideSelf = s.hideSelf();
		hideCameraOccluders = s.hideCameraOccluders();
		region = s.region();
		hideEntitiesOutsideRegion = s.hideEntitiesOutsideRegion();
		hideTerrain = s.hideTerrain();
		hideSky = s.hideSky();
		hideParticles = s.hideParticles();
		hideBlockEntities = s.hideBlockEntities();
		disableFog = s.disableFog();
		disableCameraClipping = s.disableCameraClipping();
		hideInsideBlockOverlay = s.hideInsideBlockOverlay();
		flatLighting = s.flatLighting();
		hideAllEntities = s.hideAllEntities();
		backgroundColor = s.backgroundColor();
		cacheTick = -1;
	}

	// --- decision de rendu des entites -------------------------------------------------------------

	private boolean isSelected(Entity e) {
		return uuids.contains(e.getUUID()) || ids.contains(e.getId()) || types.contains(EntityJson.typeId(e));
	}

	/** Recalcule les racines si le tick a change. Thread de rendu uniquement. */
	private void refresh(ClientLevel level, long tick) {
		if (tick == cacheTick) return;
		List<Entity> r = new ArrayList<>();
		Set<Integer> rid = new HashSet<>();
		for (Entity e : level.entitiesForRendering()) {
			if (isSelected(e)) {
				r.add(e);
				rid.add(e.getId());
			}
		}
		roots = r;
		rootIds = rid;
		cacheTick = tick;
	}

	/**
	 * Decision de rendu pour une entite, appelee depuis le mixin avec la position camera.
	 * <ol>
	 *   <li>hideAllEntities : rien n'est rendu, meme la selection</li>
	 *   <li>ni selection ni region : tout est rendu</li>
	 *   <li>entite selectionnee, passager/vehicule d'une selectionnee, ou display attache : rendue</li>
	 *   <li>region active et entite hors region : masquee (si hideEntitiesOutsideRegion)</li>
	 *   <li>entite contenant la camera : masquee (si hideCameraOccluders)</li>
	 *   <li>avec selection : joueur local masque (hideSelf), le reste masque (hideOthers)</li>
	 *   <li>region seule : le reste est rendu</li>
	 * </ol>
	 */
	public boolean shouldRender(Entity e, double camX, double camY, double camZ) {
		if (hideAllEntities) return false;
		boolean selection = hasEntitySelection();
		AABB r = region;
		if (!selection && r == null) return true;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return true;

		if (selection) {
			refresh(level, level.getGameTime());
			if (rootIds.contains(e.getId())) return true;
			if (includePassengers) {
				if (e.getVehicle() != null && rootIds.contains(e.getVehicle().getId())) return true;
				for (Entity p : e.getPassengers()) {
					if (rootIds.contains(p.getId())) return true;
				}
			}
			if (includeAttachedDisplays && EntityJson.isDisplay(e)) {
				double r2 = attachRadius * attachRadius;
				for (Entity root : roots) {
					if (e.distanceToSqr(root.position()) <= r2) return true;
				}
			}
		}

		if (r != null && hideEntitiesOutsideRegion && !e.getBoundingBox().intersects(r)) return false;

		if (hideCameraOccluders && e.getBoundingBox().inflate(0.1).contains(new Vec3(camX, camY, camZ))) return false;

		if (!selection) return true;
		if (hideSelf && e == mc.player) return false;
		return !hideOthers;
	}

	// --- etat serialise ---------------------------------------------------------------------------

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("active", isActive());

		JsonObject sel = new JsonObject();
		JsonArray u = new JsonArray();
		uuids.forEach(x -> u.add(x.toString()));
		sel.add("uuids", u);
		JsonArray i = new JsonArray();
		ids.forEach(i::add);
		sel.add("ids", i);
		JsonArray t = new JsonArray();
		types.forEach(t::add);
		sel.add("types", t);
		sel.addProperty("attachRadius", attachRadius);
		sel.addProperty("includePassengers", includePassengers);
		sel.addProperty("includeAttachedDisplays", includeAttachedDisplays);
		sel.addProperty("hideOthers", hideOthers);
		sel.addProperty("hideSelf", hideSelf);
		sel.addProperty("hideCameraOccluders", hideCameraOccluders);
		sel.addProperty("selectedNow", rootIds.size());
		o.add("entities", sel);

		AABB r = region;
		if (r != null) {
			JsonObject reg = new JsonObject();
			reg.add("from", Json.vec(r.minX, r.minY, r.minZ));
			reg.add("to", Json.vec(r.maxX - 1, r.maxY - 1, r.maxZ - 1));
			reg.addProperty("hideEntitiesOutside", hideEntitiesOutsideRegion);
			o.add("region", reg);
		} else {
			o.add("region", null);
		}

		JsonObject sc = new JsonObject();
		sc.addProperty("hideTerrain", hideTerrain);
		sc.addProperty("hideSky", hideSky);
		sc.addProperty("hideParticles", hideParticles);
		sc.addProperty("hideBlockEntities", hideBlockEntities);
		sc.addProperty("disableFog", disableFog);
		sc.addProperty("disableCameraClipping", disableCameraClipping);
		sc.addProperty("hideInsideBlockOverlay", hideInsideBlockOverlay);
		sc.addProperty("flatLighting", flatLighting);
		sc.addProperty("hideAllEntities", hideAllEntities);
		Vector4f bg = backgroundColor;
		sc.addProperty("backgroundColor", bg == null ? null : String.format(Locale.ROOT, "#%02x%02x%02x",
				Math.round(bg.x * 255), Math.round(bg.y * 255), Math.round(bg.z * 255)));
		o.add("scene", sc);
		return o;
	}
}
