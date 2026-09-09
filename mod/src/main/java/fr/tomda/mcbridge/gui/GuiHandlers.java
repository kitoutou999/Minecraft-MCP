package fr.tomda.mcbridge.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.tomda.mcbridge.McBridgeMod;
import fr.tomda.mcbridge.bridge.MainThread;
import fr.tomda.mcbridge.bridge.RpcContext;
import fr.tomda.mcbridge.bridge.RpcException;
import fr.tomda.mcbridge.bridge.RpcRouter;
import fr.tomda.mcbridge.config.BridgeConfig;
import fr.tomda.mcbridge.handlers.VisionHandlers;
import fr.tomda.mcbridge.mixin.AbstractContainerScreenAccessor;
import fr.tomda.mcbridge.util.ClientMc;
import fr.tomda.mcbridge.util.GuiCursor;
import fr.tomda.mcbridge.util.Images;
import fr.tomda.mcbridge.util.TickWaiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Inspection et capture des interfaces : inventaire du joueur et menus ouverts par le serveur.
 *
 * <p>Trois usages :
 * <ul>
 *   <li>lire le contenu d'un menu ({@code gui.state}) et le texte exact d'une infobulle
 *       ({@code gui.tooltip}) : sans image, donc sans cout en tokens ;</li>
 *   <li>photographier une case precise ({@code gui.screenshot} avec {@code crop:"slot"}) pour
 *       verifier une texture ou un modele d'objet ;</li>
 *   <li>survoler une case pour afficher son infobulle et la photographier
 *       ({@code hoverSlot} + {@code crop:"tooltip"}), pour verifier la mise en forme d'un lore.</li>
 * </ul>
 *
 * <p>L'inventaire du joueur s'ouvre cote client ({@code gui.open}), exactement comme la touche E.
 * Les menus de plugins sont ouverts par le serveur : declencher la commande correspondante avec
 * {@code chat.send}, puis lire {@code gui.state}.
 *
 * <p>Le survol utilise un curseur virtuel ({@link GuiCursor}) : la souris reelle du joueur ne bouge
 * pas, et le curseur est remis a son etat precedent apres chaque capture.
 */
public final class GuiHandlers {
	private GuiHandlers() {}

	/** Cote d'une case d'inventaire, en unites d'interface. */
	private static final int SLOT_SIZE = 16;

	/** Debord du fond d'infobulle autour du texte : trois de marge interieure plus neuf de cadre. */
	private static final int FRAME = 12;

	public static void register(RpcRouter router) {
		router.register("gui.state", ctx -> ClientMc.call(() -> state(ctx.optBoolean("includeEmpty", false),
				ctx.optBoolean("includeTooltipLineCount", true))));

		router.register("gui.open", ctx -> {
			String which = ctx.optString("screen", "inventory").toLowerCase(Locale.ROOT);
			if (!which.equals("inventory")) {
				throw RpcException.badRequest("Seul 'inventory' peut etre ouvert par le mod. Un menu de plugin "
						+ "s'ouvre par sa commande serveur (chat.send), le mod ne fait que le lire.");
			}
			return ClientMc.call(() -> {
				Minecraft mc = ClientMc.mc();
				mc.setScreen(new InventoryScreen(ClientMc.player()));
				return state(false, false);
			});
		});

		router.register("gui.close", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			String was = mc.screen != null ? mc.screen.getClass().getSimpleName() : null;
			if (mc.screen != null) mc.setScreen(null);
			GuiCursor.clear();
			JsonObject o = new JsonObject();
			o.addProperty("closed", was != null);
			o.addProperty("previousScreen", was);
			return o;
		}));

		router.register("gui.hover", ctx -> {
			if (ctx.optBoolean("clear", false)) {
				GuiCursor.clear();
				JsonObject o = new JsonObject();
				o.addProperty("active", false);
				return o;
			}
			return ClientMc.call(() -> {
				double[] pos = cursorTarget(ctx);
				GuiCursor.set(pos[0], pos[1]);
				JsonObject o = new JsonObject();
				o.addProperty("active", true);
				o.addProperty("x", pos[0]);
				o.addProperty("y", pos[1]);
				return o;
			});
		});

		router.register("gui.tooltip", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			Slot slot = requireSlot(requireContainer(), ctx.getInt("slot"));
			ItemStack stack = slot.getItem();
			JsonObject o = new JsonObject();
			o.addProperty("slot", ctx.getInt("slot"));
			o.add("item", itemJson(stack));
			if (stack.isEmpty()) {
				o.add("lines", new JsonArray());
				return o;
			}
			JsonArray lines = new JsonArray();
			for (Component line : Screen.getTooltipFromItem(mc, stack)) lines.add(lineJson(line));
			o.add("lines", lines);
			return o;
		}));

		router.register("gui.screenshot", GuiHandlers::screenshot);
		router.register("gui.click", GuiHandlers::click);
	}

	// --- etat --------------------------------------------------------------------------------------

	private static JsonObject state(boolean includeEmpty, boolean includeTooltipLineCount) throws RpcException {
		Minecraft mc = ClientMc.mc();
		JsonObject o = new JsonObject();

		JsonObject win = new JsonObject();
		int scale = mc.getWindow().getGuiScale();
		win.addProperty("guiScale", scale);
		win.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
		win.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
		win.addProperty("pixelWidth", mc.getWindow().getWidth());
		win.addProperty("pixelHeight", mc.getWindow().getHeight());
		o.add("window", win);

		JsonObject cursor = new JsonObject();
		cursor.addProperty("active", GuiCursor.active);
		if (GuiCursor.active) {
			cursor.addProperty("x", GuiCursor.x);
			cursor.addProperty("y", GuiCursor.y);
		}
		o.add("cursor", cursor);

		Screen screen = mc.screen;
		if (screen == null) {
			o.addProperty("screen", (String) null);
			o.addProperty("isContainer", false);
			return o;
		}
		o.addProperty("screen", screen.getClass().getSimpleName());
		o.addProperty("title", screen.getTitle() == null ? null : screen.getTitle().getString());
		o.addProperty("isContainer", screen instanceof AbstractContainerScreen<?>);
		if (!(screen instanceof AbstractContainerScreen<?>)) return o;

		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int left = acc.mcbridge$leftPos();
		int top = acc.mcbridge$topPos();
		JsonObject panel = new JsonObject();
		panel.addProperty("x", left);
		panel.addProperty("y", top);
		panel.addProperty("width", acc.mcbridge$imageWidth());
		panel.addProperty("height", acc.mcbridge$imageHeight());
		o.add("panel", panel);

		AbstractContainerMenu menu = acc.mcbridge$menu();
		JsonArray slots = new JsonArray();
		int filled = 0;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty()) filled++;
			if (stack.isEmpty() && !includeEmpty) continue;
			JsonObject s = new JsonObject();
			s.addProperty("slot", i);
			// slot.index vaut le numero dans le menu (affecte par addSlot) ; getContainerSlot() donne
			// la place dans le conteneur sous-jacent, differente pour la partie inventaire du joueur.
			s.addProperty("containerSlot", slot.getContainerSlot());
			s.add("rect", rect(left + slot.x, top + slot.y, SLOT_SIZE, SLOT_SIZE));
			s.add("item", itemJson(stack));
			if (includeTooltipLineCount && !stack.isEmpty()) {
				s.addProperty("tooltipLines", Screen.getTooltipFromItem(mc, stack).size());
			}
			slots.add(s);
		}
		o.add("slots", slots);
		o.addProperty("slotCount", menu.slots.size());
		o.addProperty("filledSlots", filled);
		return o;
	}

	// --- clic --------------------------------------------------------------------------------------

	/**
	 * Clique une case, exactement comme le joueur le ferait.
	 *
	 * <p>Reproduit {@code AbstractContainerScreen.slotClicked} : le numero envoye au serveur est
	 * {@code Slot.index}, c'est-a-dire la place de la case dans le menu, et non son index dans le
	 * conteneur sous-jacent. Le clic part reellement au serveur : dans un menu de plugin il
	 * declenche l'action associee (page suivante, categorie, achat), dans un inventaire il deplace
	 * des objets. D'ou la porte {@code enableGuiClicks}, fermee par defaut, la liste de types
	 * autorises, et le mode {@code dryRun}.
	 *
	 * <p>Apres le clic, le serveur peut remplacer le menu : on attend quelques ticks puis on renvoie
	 * l'etat complet de l'interface, avec ce qui a change, pour enchainer la navigation.
	 */
	private static JsonObject click(RpcContext ctx) throws Exception {
		BridgeConfig cfg = McBridgeMod.config();
		int slotIndex = ctx.getInt("slot");
		int button = ctx.optInt("button", 0);
		String typeName = ctx.optString("type", "pickup").toLowerCase(Locale.ROOT);
		boolean dryRun = ctx.optBoolean("dryRun", false);
		int waitTicks = Math.max(1, Math.min(ctx.optInt("waitTicks", 5), 200));

		ContainerInput input = switch (typeName) {
			case "pickup" -> ContainerInput.PICKUP;
			case "quick_move" -> ContainerInput.QUICK_MOVE;
			case "clone" -> ContainerInput.CLONE;
			case "throw" -> ContainerInput.THROW;
			case "swap" -> ContainerInput.SWAP;
			case "pickup_all" -> ContainerInput.PICKUP_ALL;
			case "quick_craft" -> ContainerInput.QUICK_CRAFT;
			default -> throw RpcException.badRequest("Type de clic inconnu : " + typeName
					+ ". Valeurs possibles : pickup, quick_move, clone, throw, swap, pickup_all, quick_craft.");
		};

		// Ce qui serait clique, calcule avant toute verification pour rester informatif en dryRun.
		JsonObject target = ClientMc.call(() -> {
			AbstractContainerMenu menu = requireContainer();
			Slot slot = requireSlot(menu, slotIndex);
			JsonObject o = new JsonObject();
			o.addProperty("slot", slotIndex);
			o.addProperty("sentSlotId", slot.index);
			o.addProperty("containerSlot", slot.getContainerSlot());
			o.addProperty("containerId", menu.containerId);
			o.addProperty("button", button);
			o.addProperty("type", typeName);
			o.add("item", itemJson(slot.getItem()));
			o.addProperty("screen", ClientMc.mc().screen.getClass().getSimpleName());
			return o;
		});

		if (dryRun) {
			JsonObject o = new JsonObject();
			o.addProperty("sent", false);
			o.addProperty("dryRun", true);
			o.add("would", target);
			return o;
		}
		if (!cfg.enableGuiClicks) {
			throw RpcException.unavailable("Les clics sont desactives. Contrairement au reste du mod, un clic modifie l'etat "
					+ "du serveur (navigation, mais aussi achat ou deplacement d'objets selon le menu). Passer enableGuiClicks "
					+ "a true dans mcbridge.json pour l'autoriser, ou utiliser dryRun:true pour voir ce qui serait clique.");
		}
		if (!cfg.allowedClickTypes.contains(typeName)) {
			throw RpcException.forbidden("Type de clic '" + typeName + "' non autorise. Autorises : "
					+ String.join(", ", cfg.allowedClickTypes) + " (champ allowedClickTypes de mcbridge.json).");
		}

		ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			if (mc.gameMode == null) throw RpcException.noPlayer();
			AbstractContainerMenu menu = requireContainer();
			Slot slot = requireSlot(menu, slotIndex);
			mc.gameMode.handleContainerInput(menu.containerId, slot.index, button, input, ClientMc.player());
			return null;
		});
		MainThread.await(TickWaiter.after(waitTicks), waitTicks * 50L + 5000L);

		JsonObject after = ClientMc.call(() -> {
			JsonObject st = state(false, false);
			// Un objet reste sur le curseur si le menu ne l'a pas annule : le signaler, car il
			// suivrait le joueur hors de l'interface.
			Minecraft mc = ClientMc.mc();
			if (mc.screen instanceof AbstractContainerScreen<?>) {
				ItemStack carried = ((AbstractContainerScreenAccessor) mc.screen).mcbridge$menu().getCarried();
				if (!carried.isEmpty()) st.add("carried", itemJson(carried));
			}
			return st;
		});

		JsonObject o = new JsonObject();
		o.addProperty("sent", true);
		o.add("clicked", target);
		o.add("after", after);
		String beforeScreen = target.get("screen").getAsString();
		String afterScreen = after.has("screen") && !after.get("screen").isJsonNull()
				? after.get("screen").getAsString() : null;
		o.addProperty("screenChanged", !beforeScreen.equals(afterScreen));
		o.addProperty("menuReplaced", after.has("panel")
				&& target.get("containerId").getAsInt() != currentContainerId());
		return o;
	}

	private static int currentContainerId() {
		Minecraft mc = ClientMc.mc();
		if (mc.screen instanceof AbstractContainerScreen<?>) {
			return ((AbstractContainerScreenAccessor) mc.screen).mcbridge$menu().containerId;
		}
		return -1;
	}

	// --- capture -----------------------------------------------------------------------------------

	private static JsonObject screenshot(RpcContext ctx) throws Exception {
		BridgeConfig cfg = McBridgeMod.config();
		if (!cfg.enableVision) throw RpcException.unavailable("La vision est desactivee (enableVision=false).");
		Minecraft mc = ClientMc.mc();

		String crop = ctx.optString("crop", "gui").toLowerCase(Locale.ROOT);
		int padding = ctx.optInt("padding", switch (crop) {
			case "slot" -> 2;
			case "tooltip" -> 6;
			default -> 6;
		});
		int waitTicks = Math.max(1, Math.min(ctx.optInt("waitTicks", 3), 200));
		int maxWidth = ctx.optInt("maxWidth", 900);
		int minWidth = ctx.optInt("minWidth", crop.equals("slot") ? 256 : 0);
		String format = ctx.optString("format", "png").toLowerCase(Locale.ROOT);
		double quality = ctx.optDouble("quality", cfg.screenshot.jpegQuality);
		boolean hideHud = ctx.optBoolean("hideHud", true);
		Integer hoverSlot = ctx.has("hoverSlot") ? ctx.getInt("hoverSlot") : null;

		// Curseur virtuel le temps de la capture, puis retour a l'etat precedent.
		boolean hadCursor = GuiCursor.active;
		double prevX = GuiCursor.x;
		double prevY = GuiCursor.y;
		try {
			JsonObject info = ClientMc.call(() -> {
				if (mc.screen == null) {
					throw RpcException.notFound("Aucun ecran ouvert. Utiliser gui.open pour l'inventaire du joueur, "
							+ "ou declencher le menu par sa commande serveur.");
				}
				JsonObject meta = new JsonObject();
				if (hoverSlot != null) {
					Slot slot = requireSlot(requireContainer(), hoverSlot);
					AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) mc.screen;
					double cx = acc.mcbridge$leftPos() + slot.x + SLOT_SIZE / 2.0;
					double cy = acc.mcbridge$topPos() + slot.y + SLOT_SIZE / 2.0;
					GuiCursor.set(cx, cy);
					meta.addProperty("hoverSlot", hoverSlot);
					meta.addProperty("hoverX", cx);
					meta.addProperty("hoverY", cy);
					meta.add("tooltipRect", tooltipRect(mc, slot.getItem(), cx, cy));
				}
				meta.addProperty("screen", mc.screen.getClass().getSimpleName());
				return meta;
			});

			byte[] png = VisionHandlers.captureRawPng(cfg, mc, hideHud, false, waitTicks);

			int[] region = ClientMc.call(() -> cropRegion(mc, crop, ctx, info, padding));
			JsonObject image = Images.encodeRegion(png, region, maxWidth, minWidth, format, quality);
			image.add("gui", info);
			image.addProperty("cropMode", crop);
			return image;
		} finally {
			if (hadCursor) GuiCursor.set(prevX, prevY);
			else GuiCursor.clear();
		}
	}

	/** Zone a decouper, en pixels physiques (le framebuffer est a l'echelle guiScale). */
	private static int[] cropRegion(Minecraft mc, String crop, RpcContext ctx, JsonObject info, int padding) throws RpcException {
		int scale = Math.max(1, mc.getWindow().getGuiScale());
		switch (crop) {
			case "none", "full" -> {
				return null;
			}
			case "gui", "panel" -> {
				AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) requireContainerScreen();
				return scaled(acc.mcbridge$leftPos(), acc.mcbridge$topPos(),
						acc.mcbridge$imageWidth(), acc.mcbridge$imageHeight(), padding, scale, mc);
			}
			case "slot" -> {
				int index = ctx.has("slot") ? ctx.getInt("slot")
						: (ctx.has("hoverSlot") ? ctx.getInt("hoverSlot") : -1);
				if (index < 0) throw RpcException.badRequest("crop:'slot' demande le parametre 'slot' (ou 'hoverSlot').");
				Slot slot = requireSlot(requireContainer(), index);
				AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) requireContainerScreen();
				return scaled(acc.mcbridge$leftPos() + slot.x, acc.mcbridge$topPos() + slot.y,
						SLOT_SIZE, SLOT_SIZE, padding, scale, mc);
			}
			case "tooltip" -> {
				if (!info.has("tooltipRect")) {
					throw RpcException.badRequest("crop:'tooltip' demande 'hoverSlot' : l'infobulle s'affiche au survol.");
				}
				JsonObject r = info.getAsJsonObject("tooltipRect");
				return scaled(r.get("x").getAsInt(), r.get("y").getAsInt(),
						r.get("width").getAsInt(), r.get("height").getAsInt(), padding, scale, mc);
			}
			case "rect" -> {
				JsonObject r = ctx.optObject("rect");
				if (r == null) throw RpcException.badRequest("crop:'rect' demande 'rect' {x, y, width, height} en unites d'interface.");
				return scaled(r.get("x").getAsInt(), r.get("y").getAsInt(),
						r.get("width").getAsInt(), r.get("height").getAsInt(), padding, scale, mc);
			}
			default -> throw RpcException.badRequest("crop inconnu : " + crop
					+ ". Valeurs possibles : none, gui, slot, tooltip, rect.");
		}
	}

	private static int[] scaled(int x, int y, int w, int h, int padding, int scale, Minecraft mc) {
		int px = (x - padding) * scale;
		int py = (y - padding) * scale;
		int pw = (w + padding * 2) * scale;
		int ph = (h + padding * 2) * scale;
		int maxW = mc.getWindow().getWidth();
		int maxH = mc.getWindow().getHeight();
		px = Math.max(0, Math.min(px, maxW - 1));
		py = Math.max(0, Math.min(py, maxH - 1));
		return new int[]{px, py, Math.min(pw, maxW - px), Math.min(ph, maxH - py)};
	}

	/**
	 * Position et taille du fond de l'infobulle, calculees comme le fait le jeu.
	 *
	 * <p>Reprend la mise en page vanilla : la zone de texte mesure la plus grande largeur de ligne
	 * sur dix pixels par ligne (moins deux pour une ligne unique), et le fond deborde de cette zone
	 * de douze pixels sur chaque cote (trois de marge interieure plus neuf de cadre). L'ancre est le
	 * curseur decale de douze pixels vers la droite et douze vers le haut, avec repli a gauche si
	 * l'infobulle depasse a droite, et remontee si elle depasse en bas. Un pack de ressources peut
	 * changer l'apparence du cadre mais pas sa geometrie.
	 */
	private static JsonObject tooltipRect(Minecraft mc, ItemStack stack, double mouseX, double mouseY) {
		int width = 0;
		int lines = 0;
		if (!stack.isEmpty()) {
			for (Component line : Screen.getTooltipFromItem(mc, stack)) {
				width = Math.max(width, mc.font.width(line));
				lines++;
			}
		}
		int height = (lines == 1 ? -2 : 0) + lines * 10;
		int paddedWidth = width + FRAME + FRAME;
		int paddedHeight = height + FRAME + FRAME;
		int x = (int) mouseX + 12;
		int y = (int) mouseY - 12;
		int screenW = mc.getWindow().getGuiScaledWidth();
		int screenH = mc.getWindow().getGuiScaledHeight();
		if (x + width > screenW) x = Math.max(x - 24 - width, 4);
		if (y + paddedHeight > screenH) y = screenH - paddedHeight;
		return rect(x - FRAME, y - FRAME, paddedWidth, paddedHeight);
	}

	// --- helpers -----------------------------------------------------------------------------------

	private static AbstractContainerScreen<?> requireContainerScreen() throws RpcException {
		Screen screen = ClientMc.mc().screen;
		if (screen == null) {
			throw RpcException.notFound("Aucun ecran ouvert. Utiliser gui.open pour l'inventaire du joueur, "
					+ "ou declencher le menu par sa commande serveur.");
		}
		if (!(screen instanceof AbstractContainerScreen<?> container)) {
			throw RpcException.badRequest("L'ecran ouvert (" + screen.getClass().getSimpleName()
					+ ") n'a pas de cases : ce n'est pas un inventaire ni un menu de conteneur.");
		}
		return container;
	}

	private static AbstractContainerMenu requireContainer() throws RpcException {
		return ((AbstractContainerScreenAccessor) requireContainerScreen()).mcbridge$menu();
	}

	private static Slot requireSlot(AbstractContainerMenu menu, int index) throws RpcException {
		if (index < 0 || index >= menu.slots.size()) {
			throw RpcException.badRequest("Case " + index + " hors limites (0 a " + (menu.slots.size() - 1) + ").");
		}
		return menu.slots.get(index);
	}

	private static JsonObject rect(int x, int y, int w, int h) {
		JsonObject o = new JsonObject();
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("width", w);
		o.addProperty("height", h);
		return o;
	}

	private static double[] cursorTarget(RpcContext ctx) throws RpcException {
		if (ctx.has("slot")) {
			Slot slot = requireSlot(requireContainer(), ctx.getInt("slot"));
			AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) requireContainerScreen();
			return new double[]{acc.mcbridge$leftPos() + slot.x + SLOT_SIZE / 2.0,
					acc.mcbridge$topPos() + slot.y + SLOT_SIZE / 2.0};
		}
		if (ctx.has("x") && ctx.has("y")) return new double[]{ctx.getDouble("x"), ctx.getDouble("y")};
		throw RpcException.badRequest("Fournir 'slot', ou 'x' et 'y' en unites d'interface, ou clear:true.");
	}

	private static JsonObject itemJson(ItemStack stack) {
		JsonObject o = new JsonObject();
		o.addProperty("empty", stack.isEmpty());
		if (stack.isEmpty()) return o;
		o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		o.addProperty("count", stack.getCount());
		o.addProperty("name", stack.getHoverName().getString());
		return o;
	}

	/** Une ligne d'infobulle : texte brut, plus les segments avec leur mise en forme. */
	private static JsonObject lineJson(Component line) {
		JsonObject o = new JsonObject();
		o.addProperty("text", line.getString());
		JsonArray segments = new JsonArray();
		line.visit((style, text) -> {
			if (!text.isEmpty()) segments.add(segmentJson(style, text));
			return Optional.empty();
		}, Style.EMPTY);
		o.add("segments", segments);
		return o;
	}

	private static JsonObject segmentJson(Style style, String text) {
		JsonObject o = new JsonObject();
		o.addProperty("text", text);
		TextColor color = style.getColor();
		o.addProperty("color", color == null ? null : color.serialize());
		if (style.isBold()) o.addProperty("bold", true);
		if (style.isItalic()) o.addProperty("italic", true);
		if (style.isUnderlined()) o.addProperty("underlined", true);
		if (style.isStrikethrough()) o.addProperty("strikethrough", true);
		if (style.isObfuscated()) o.addProperty("obfuscated", true);
		return o;
	}
}
