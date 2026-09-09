package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.util.CaptureState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Masque l'ecran ouvert pendant une capture, sans le fermer.
 *
 * <p>Depuis 26.x, l'interface est "extraite" avant d'etre dessinee :
 * {@code GameRenderer.extractGui} appelle
 * {@code Screen.extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor, int, int, float)}
 * pour l'ecran courant, ce qui inclut ses widgets, le flou de fond et, pour le chat, l'historique
 * des messages (que F1 ne masque pas). Annuler cet appel suffit a obtenir une frame sans
 * interface tout en laissant l'ecran ouvert et la souris liberee. Verifie sur Minecraft 26.1.2.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
	private void mcbridge$hideDuringCapture(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (CaptureState.hideScreens) ci.cancel();
	}
}
