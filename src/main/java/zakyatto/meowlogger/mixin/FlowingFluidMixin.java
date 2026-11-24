package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.FluidCapture;

/** Lets fluid flowing from a ticking fluid block inherit the player who placed it. */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
	@WrapMethod(method = "tick")
	private void meowlogger$trackPlacer(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, Operation<Void> original) {
		FluidCapture.beginTick(level, pos);
		try {
			original.call(level, pos, blockState, fluidState);
		} finally {
			FluidCapture.endTick(level, pos);
		}
	}
}
