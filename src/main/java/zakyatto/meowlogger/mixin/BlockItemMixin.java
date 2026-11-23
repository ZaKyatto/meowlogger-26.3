package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.BlockCapture;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@WrapMethod(method = "placeBlock")
	private boolean meowlogger$logPlacement(BlockPlaceContext context, BlockState state, Operation<Boolean> original) {
		if (!(context.getPlayer() instanceof ServerPlayer player)) {
			return original.call(context, state);
		}
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState oldState = level.getBlockState(pos);
		boolean placed = original.call(context, state);
		if (placed) {
			BlockCapture.onPlace(player, level, pos, oldState, level.getBlockState(pos));
		}
		return placed;
	}
}
