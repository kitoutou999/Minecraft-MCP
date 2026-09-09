package fr.tomda.mcbridge.mixin.compat;

import fr.tomda.mcbridge.focus.FocusState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy dessine un terrain lointain (LOD) hors du pipeline des sections vanilla, via
 * {@code VoxyRenderSystem.renderOpaque}. On l'annule quand le terrain est masque ou qu'une region
 * est active (les LOD ne connaissent pas la region). {@code @Pseudo} : ignore si Voxy est absent.
 * Verifie sur Voxy 0.2.18 pour 26.1.2.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class VoxyRenderSystemMixin {
	@Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, require = 0)
	private void mcbridge$skipLod(CallbackInfo ci) {
		FocusState f = FocusState.INSTANCE;
		if (f.hideTerrain() || f.isRegionActive()) ci.cancel();
	}
}
