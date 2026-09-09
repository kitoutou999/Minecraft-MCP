package fr.tomda.mcbridge.util;

/**
 * Curseur virtuel : fait croire a l'interface que la souris se trouve ailleurs, sans toucher au
 * curseur reel du systeme.
 *
 * <p>C'est ce qui permet de survoler une case d'inventaire pour en afficher l'infobulle : les
 * ecrans recoivent la position de la souris via {@code MouseHandler.getScaledXPos/YPos}, que
 * {@code MouseHandlerMixin} detourne quand ce curseur est actif. Les coordonnees sont exprimees
 * dans le repere de l'interface (unites mises a l'echelle par guiScale), comme celles des cases.
 */
public final class GuiCursor {
	public static volatile boolean active = false;
	public static volatile double x = 0;
	public static volatile double y = 0;

	private GuiCursor() {}

	public static void set(double guiX, double guiY) {
		x = guiX;
		y = guiY;
		active = true;
	}

	public static void clear() {
		active = false;
	}
}
