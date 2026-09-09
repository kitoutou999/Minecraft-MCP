package fr.tomda.mcbridge.mixin.compat;

import fr.tomda.mcbridge.focus.FocusState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ceinture de securite pour Sodium : le terrain est deja coupe par {@code ChunkSectionsToRenderMixin}
 * et les block entities par {@code LevelRendererMixin}, mais on annule aussi les points d'entree
 * propres a Sodium ({@code drawChunkLayer}, {@code extractBlockEntities}) au cas ou une version
 * future les appellerait directement. {@code @Pseudo} : ignore si Sodium est absent.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererMixin {
	@Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 0)
	private void mcbridge$skipTerrain(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideTerrain()) ci.cancel();
	}

	@Inject(method = "extractBlockEntities", at = @At("HEAD"), cancellable = true, require = 0)
	private void mcbridge$skipBlockEntities(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideBlockEntities()) ci.cancel();
	}
}
