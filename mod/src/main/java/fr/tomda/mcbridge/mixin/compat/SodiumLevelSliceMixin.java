package fr.tomda.mcbridge.mixin.compat;

import fr.tomda.mcbridge.focus.FocusState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Focus par region, chemin Sodium : le maillage des sections lit les blocs via
 * {@code net.caffeinemc.mods.sodium.client.world.LevelSlice}. Mixin {@code @Pseudo} : ignore si
 * Sodium est absent, aucune dependance de compilation (la classe est visee par son nom).
 * Verifie sur Sodium 0.9.1 pour 26.1.2.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public abstract class SodiumLevelSliceMixin {
	@Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void mcbridge$regionBlockPos(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (!FocusState.INSTANCE.isBlockVisible(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void mcbridge$regionBlockXyz(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		if (!FocusState.INSTANCE.isBlockVisible(x, y, z)) {
			cir.setReturnValue(Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void mcbridge$regionFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (!FocusState.INSTANCE.isBlockVisible(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}
}
