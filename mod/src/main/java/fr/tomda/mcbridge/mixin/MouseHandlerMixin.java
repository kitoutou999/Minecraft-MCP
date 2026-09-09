package fr.tomda.mcbridge.mixin;

import com.mojang.blaze3d.platform.Window;
import fr.tomda.mcbridge.util.GuiCursor;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Curseur virtuel pour les captures d'interface.
 *
 * <p>{@code GameRenderer.extractGui} lit la position de la souris via
 * {@code mouseHandler.getScaledXPos(window)} et la transmet a l'ecran ouvert, qui en deduit la case
 * survolee et affiche l'infobulle correspondante. Detourner ces deux lectures suffit donc a
 * survoler une case sans deplacer le curseur reel du joueur. Seules les surcharges d'instance sont
 * visees ; les variantes statiques, utilisees pour convertir des deltas, restent intactes.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
	private void mcbridge$virtualX(Window window, CallbackInfoReturnable<Double> cir) {
		if (GuiCursor.active) cir.setReturnValue(GuiCursor.x);
	}

	@Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
	private void mcbridge$virtualY(Window window, CallbackInfoReturnable<Double> cir) {
		if (GuiCursor.active) cir.setReturnValue(GuiCursor.y);
	}
}
