package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overlay "camera dans un bloc" : {@code getViewBlockingState} renvoie le bloc qui bouche la vue en
 * premiere personne, dont la texture est dessinee plein ecran. Renvoyer null supprime l'overlay.
 * (Deja absent en spectateur, car {@code player.noPhysics} est vrai.)
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true)
	private static void mcbridge$noViewBlocking(Player player, CallbackInfoReturnable<BlockState> cir) {
		if (FocusState.INSTANCE.hideInsideBlockOverlay()) cir.setReturnValue(null);
	}
}
