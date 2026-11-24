package zakyatto.meowlogger.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zakyatto.meowlogger.capture.FireCapture;

/** Notices every fire block appearing, so lit fires are logged and spreading fires keep their igniter. */
@Mixin(BaseFireBlock.class)
abstract class BaseFireBlockMixin {
	@Inject(method = "onPlace", at = @At("HEAD"))
	private void meowlogger$firePlaced(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston,
			CallbackInfo ci) {
		if (!level.isClientSide() && !oldState.is(state.getBlock())) {
			FireCapture.firePlaced(level, pos, oldState);
		}
	}
}
