package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import zakyatto.meowlogger.capture.BlockCapture;
import zakyatto.meowlogger.capture.DecorationCapture;

/**
 * Wraps player block breaking, since Fabric's after-break event misses blocks removed as a side
 * effect, and item use, so decorations it spawns are credited to the player.
 */
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

	@WrapMethod(method = "useItem")
	private InteractionResult meowlogger$trackItemUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
			Operation<InteractionResult> original) {
		ServerPlayer previous = DecorationCapture.beginItemUse(player);
		try {
			return original.call(player, level, stack, hand);
		} finally {
			DecorationCapture.endItemUse(previous);
		}
	}

	@WrapMethod(method = "useItemOn")
	private InteractionResult meowlogger$trackItemUseOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
			BlockHitResult hit, Operation<InteractionResult> original) {
		ServerPlayer previous = DecorationCapture.beginItemUse(player);
		try {
			return original.call(player, level, stack, hand, hit);
		} finally {
			DecorationCapture.endItemUse(previous);
		}
	}
}
