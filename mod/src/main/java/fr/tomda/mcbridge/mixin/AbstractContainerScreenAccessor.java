package fr.tomda.mcbridge.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expose la position et la taille du panneau d'un ecran de conteneur, ainsi que son menu.
 * Ces champs sont proteges : sans cet accesseur, impossible de convertir la position d'une case en
 * coordonnees d'ecran pour la survoler ou la decouper dans une capture.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int mcbridge$leftPos();

	@Accessor("topPos")
	int mcbridge$topPos();

	@Accessor("imageWidth")
	int mcbridge$imageWidth();

	@Accessor("imageHeight")
	int mcbridge$imageHeight();

	@Accessor("menu")
	AbstractContainerMenu mcbridge$menu();
}
