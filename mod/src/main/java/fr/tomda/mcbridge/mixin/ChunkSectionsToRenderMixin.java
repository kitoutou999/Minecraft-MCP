package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Masquage du terrain.
 *
 * <p>{@code ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)} dessine les
 * sections opaques puis translucides depuis {@code addMainPass}. Sodium ne remplace pas cette
 * methode : il s'y injecte pour dessiner ses propres sections. En annulant a l'entree avec une
 * priorite superieure, le terrain disparait avec ou sans Sodium.
 */
@Mixin(value = ChunkSectionsToRender.class, priority = 2000)
public abstract class ChunkSectionsToRenderMixin {
	@Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
	private void mcbridge$skipTerrain(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideTerrain()) ci.cancel();
	}
}
