package zakyatto.meowlogger.capture;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/** Turns player block breaks and placements into queued {@link ActionRecord}s. */
public final class BlockCapture {
	private BlockCapture() {
	}

	/** Registers the Fabric API listeners. Call once from the mod initializer. */
	public static void register() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) ->
				log(ActionType.BLOCK_BREAK, player, level, pos, state, level.getBlockState(pos)));
	}

	/** Called by the placement mixin after a player successfully placed a block on the server. */
	public static void onPlace(Player player, Level level, BlockPos pos, BlockState oldState, BlockState newState) {
		log(ActionType.BLOCK_PLACE, player, level, pos, oldState, newState);
	}

	private static void log(ActionType type, Player player, Level level, BlockPos pos, BlockState oldState, BlockState newState) {
		MeowLogger.log(new ActionRecord(System.currentTimeMillis(), type, player.getUUID(),
				player.getGameProfile().name(), level.dimension(), pos, oldState, newState));
	}
}
