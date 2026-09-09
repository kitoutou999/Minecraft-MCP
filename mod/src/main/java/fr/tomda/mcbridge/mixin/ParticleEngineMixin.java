package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Masquage des particules : {@code ParticleEngine.extract} remplit l'etat de rendu des particules
 * de la frame ; annule, rien n'est soumis au rendu (les particules continuent de vivre et de tick).
 */
@Mixin(value = ParticleEngine.class, priority = 2000)
public abstract class ParticleEngineMixin {
	@Inject(method = "extract", at = @At("HEAD"), cancellable = true)
	private void mcbridge$skipParticles(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideParticles()) ci.cancel();
	}
}
