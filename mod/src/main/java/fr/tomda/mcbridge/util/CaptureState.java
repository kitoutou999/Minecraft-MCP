package fr.tomda.mcbridge.util;

/**
 * Etat global d'une capture en cours, consulte par les mixins de rendu.
 *
 * <p>{@link #hideScreens} : quand vrai, l'ecran ouvert (chat, menu Echap, inventaire, menu d'un
 * plugin) n'est pas extrait pour le rendu. Il reste ouvert et garde la souris liberee : le
 * developpeur qui a ouvert le chat pour sortir du jeu ne perd rien. Toujours remis a faux dans un
 * bloc finally par le handler qui l'a active.
 */
public final class CaptureState {
	public static volatile boolean hideScreens = false;

	private CaptureState() {}
}
