package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.decoration.BlockAttachedEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import zakyatto.meowlogger.capture.DecorationCapture;

/** Logs item frames and paintings that drop because the block they hung on is gone. */
@Mixin(BlockAttachedEntity.class)
abstract class BlockAttachedEntityMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/BlockAttachedEntity;discard()V"))
	private void meowlogger$logSupportLost(BlockAttachedEntity entity, Operation<Void> original) {
		DecorationCapture.supportLost(entity);
		original.call(entity);
	}
}
