package fr.tomda.mcbridge.mixin;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Focus par region, chemin vanilla : {@code SectionCompiler} lit les blocs d'une section a travers
 * {@code RenderSectionRegion}. Hors de la region active, tout bloc est vu comme de l'air et tout
 * fluide comme vide ; les faces en bordure de region sont donc dessinees. Une reconstruction des
 * sections est necessaire apres chaque changement (faite par {@code FocusHandlers}).
 * Avec Sodium, le meme travail est fait par {@code compat.SodiumLevelSliceMixin}.
 */
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void mcbridge$regionBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (!FocusState.INSTANCE.isBlockVisible(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void mcbridge$regionFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (!FocusState.INSTANCE.isBlockVisible(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}
}
