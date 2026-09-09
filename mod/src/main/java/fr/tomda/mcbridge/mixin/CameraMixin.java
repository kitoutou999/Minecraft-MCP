package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clipping de la camera en 3e personne : {@code Camera.getMaxZoom(float)} rapproche la camera
 * quand un bloc se trouve derriere le joueur. Renvoyer la distance demandee desactive ce recul.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void mcbridge$noClipping(float cameraDist, CallbackInfoReturnable<Float> cir) {
		if (FocusState.INSTANCE.disableCameraClipping()) cir.setReturnValue(cameraDist);
	}
}
