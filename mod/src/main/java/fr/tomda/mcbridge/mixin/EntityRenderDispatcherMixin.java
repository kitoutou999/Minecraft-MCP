package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Filtre de rendu des entites pour le mode focus.
 *
 * <p>{@code EntityRenderDispatcher.shouldRender(E, Frustum, double, double, double)} est le test
 * de visibilite appele par {@code LevelRenderer} pour chaque entite candidate ; les trois doubles
 * sont la position de la camera. Renvoyer {@code false} suffit a exclure l'entite de la frame,
 * sans toucher a son etat ni aux paquets serveur. Verifie sur Minecraft 26.1.2.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	/**
	 * Eclairage plat : {@code extractEntity} produit l'etat de rendu d'une entite pour la frame,
	 * dont {@code lightCoords} (lumiere bloc et ciel empaquetees). 15728880 (0xF000F0) est la valeur
	 * plein jour utilisee par defaut dans {@code EntityRenderState} : la cible reste lisible de nuit,
	 * dans une piece fermee ou dans le Nether.
	 */
	@Inject(method = "extractEntity", at = @At("RETURN"))
	private void mcbridge$flatLighting(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
		if (FocusState.INSTANCE.flatLighting()) {
			EntityRenderState state = cir.getReturnValue();
			if (state != null) state.lightCoords = 15728880;
		}
	}

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void mcbridge$applyFocus(Entity entity, Frustum frustum, double camX, double camY, double camZ,
	                                 CallbackInfoReturnable<Boolean> cir) {
		if (!FocusState.INSTANCE.shouldRender(entity, camX, camY, camZ)) {
			cir.setReturnValue(false);
		}
	}
}
