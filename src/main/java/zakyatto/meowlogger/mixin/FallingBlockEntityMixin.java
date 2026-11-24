package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import zakyatto.meowlogger.capture.FallingCapture;

/** Logs blocks starting to fall and landing. */
@Mixin(FallingBlockEntity.class)
abstract class FallingBlockEntityMixin {
	@WrapMethod(method = "fall")
	private static FallingBlockEntity meowlogger$logFall(Level level, BlockPos pos, BlockState state, Operation<FallingBlockEntity> original) {
		BlockState before = level.getBlockState(pos);
		FallingBlockEntity entity = original.call(level, pos, state);
		if (!level.isClientSide()) {
			FallingCapture.fell(level, pos.immutable(), before, entity);
		}
		return entity;
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean meowlogger$logLanding(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
		BlockState before = level.getBlockState(pos);
		boolean placed = original.call(level, pos, state);
		if (placed && !level.isClientSide()) {
			FallingCapture.landed((FallingBlockEntity) (Object) this, level, pos.immutable(), before);
		}
		return placed;
	}
}
