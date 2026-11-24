package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.DecorationCapture;

/** Logs decorations a player placed, once they are actually in the world. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@WrapMethod(method = "addFreshEntity")
	private boolean meowlogger$logPlacement(Entity entity, Operation<Boolean> original) {
		boolean added = original.call(entity);
		if (added) {
			DecorationCapture.entityAdded(entity);
		}
		return added;
	}
}
