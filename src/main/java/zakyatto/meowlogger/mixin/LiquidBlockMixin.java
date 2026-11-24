package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zakyatto.meowlogger.capture.FluidCapture;

/** Logs fluid flowing into a space or washing a block away, and lava turning into cobblestone, obsidian or basalt. */
@Mixin(LiquidBlock.class)
abstract class LiquidBlockMixin {
	@Inject(method = "onPlace", at = @At("HEAD"))
	private void meowlogger$liquidPlaced(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston,
			CallbackInfo ci) {
		if (!level.isClientSide() && !oldState.is(state.getBlock())) {
			FluidCapture.liquidPlaced(level, pos, state, oldState);
		}
	}

	@WrapMethod(method = "shouldSpreadLiquid")
	private boolean meowlogger$logSolidify(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
		if (level.isClientSide()) {
			return original.call(level, pos, state);
		}
		BlockState before = level.getBlockState(pos);
		boolean spreads = original.call(level, pos, state);
		FluidCapture.maybeSolidified(level, pos, before);
		return spreads;
	}
}
