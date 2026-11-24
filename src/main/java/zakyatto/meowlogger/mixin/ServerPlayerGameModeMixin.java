package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import zakyatto.meowlogger.capture.BlockCapture;

/** Wraps player block breaking, since Fabric's after-break event misses blocks removed as a side effect. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@Shadow
	protected ServerLevel level;

	@WrapMethod(method = "destroyBlock")
	private boolean meowlogger$logBreak(BlockPos pos, Operation<Boolean> original) {
		BlockCapture.BreakSnapshot snapshot = BlockCapture.beforeBreak(level, pos);
		boolean destroyed = original.call(pos);
		BlockCapture.afterBreak(player, level, snapshot);
		return destroyed;
	}
}
