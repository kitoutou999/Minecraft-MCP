package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brouillard : {@code FogRenderer.updateBuffer(FogData)} ecrit les parametres de brouillard de la
 * frame dans le tampon GPU. Repousser debut et fin a l'infini supprime le brouillard sur les entites
 * et le terrain ; aligner la couleur sur le fond evite toute teinte residuelle.
 */
@Mixin(value = FogRenderer.class, priority = 2000)
public abstract class FogRendererMixin {
	@Inject(method = "updateBuffer", at = @At("HEAD"))
	private void mcbridge$adjustFog(FogData fog, CallbackInfo ci) {
		FocusState f = FocusState.INSTANCE;
		if (f.disableFog()) {
			fog.environmentalStart = 1.0E6F;
			fog.renderDistanceStart = 1.0E6F;
			fog.environmentalEnd = 1.0E7F;
			fog.renderDistanceEnd = 1.0E7F;
			fog.skyEnd = 1.0E7F;
			fog.cloudEnd = 1.0E7F;
		}
		Vector4f bg = f.backgroundColor();
		if (bg != null) fog.color.set(bg.x, bg.y, bg.z, fog.color.w);
	}
}
