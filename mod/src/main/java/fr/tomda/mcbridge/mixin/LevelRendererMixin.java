package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Masquage du ciel, des nuages, de la meteo et des block entities ; couleur de fond.
 *
 * <p>Dans 26.1.2, {@code LevelRenderer.renderLevel} efface l'ecran avec {@code fogColor} puis ajoute
 * les passes du frame graph ({@code addSkyPass}, {@code addMainPass}, {@code addCloudsPass},
 * {@code addWeatherPass}). Annuler une passe a son entree suffit a la supprimer. Priorite 2000 :
 * applique apres Sodium, Iris et Voxy (priorite 1000) qui injectent dans les memes methodes, pour que
 * nos annulations passent en premier a l'execution.
 */
@Mixin(value = LevelRenderer.class, priority = 2000)
public abstract class LevelRendererMixin {
	@Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
	private void mcbridge$skipSky(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideSky()) ci.cancel();
	}

	@Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
	private void mcbridge$skipClouds(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideSky()) ci.cancel();
	}

	@Inject(method = "addWeatherPass", at = @At("HEAD"), cancellable = true)
	private void mcbridge$skipWeather(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideSky()) ci.cancel();
	}

	/** Sodium injecte ici aussi pour iterer ses propres sections : notre annulation s'execute avant. */
	@Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"), cancellable = true)
	private void mcbridge$skipBlockEntities(CallbackInfo ci) {
		if (FocusState.INSTANCE.hideBlockEntities()) ci.cancel();
	}

	/** La couleur d'effacement de l'ecran devient la couleur de fond du studio. */
	@ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true)
	private Vector4f mcbridge$background(Vector4f fogColor) {
		Vector4f bg = FocusState.INSTANCE.backgroundColor();
		return bg != null ? bg : fogColor;
	}
}
