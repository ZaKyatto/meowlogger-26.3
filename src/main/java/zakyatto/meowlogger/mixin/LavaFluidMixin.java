package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.FluidCapture;

/** Logs lava flowing into water, which turns the water into stone. */
@Mixin(LavaFluid.class)
abstract class LavaFluidMixin {
	@WrapMethod(method = "spreadTo")
	private void meowlogger$logStone(LevelAccessor accessor, BlockPos pos, BlockState state, Direction direction, FluidState fluid,
			Operation<Void> original) {
		if (!(accessor instanceof Level level) || level.isClientSide()) {
			original.call(accessor, pos, state, direction, fluid);
			return;
		}
		BlockState before = level.getBlockState(pos);
		original.call(accessor, pos, state, direction, fluid);
		FluidCapture.maybeSolidified(level, pos, before);
	}
}
