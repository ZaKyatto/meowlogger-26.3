package zakyatto.meowlogger.capture;

import java.util.Map;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.action.ActionType;

/**
 * Logs sand, gravel, anvils, concrete powder and other falling blocks leaving their place and
 * landing elsewhere. A fall is credited to the player who changed the block under it (or placed it)
 * shortly before; a falling column passes that player on to each block above. Server thread only.
 */
public final class FallingCapture {
	/** The source of falls and landings, the falling block entity's type. */
	public static final String FALLING_SOURCE = "minecraft:falling_block";
	/** How long after a player's change a fall it triggered is still credited to them: 10 seconds. */
	private static final long CAUSE_TICKS = 200;

	private static final Lineage causes = new Lineage((level, pos) -> true, CAUSE_TICKS); // pruned by age only
	private static final Map<FallingBlockEntity, Lineage.Responsible> falling = new WeakHashMap<>();

	private FallingCapture() {
	}

	/** Registers cleanup when the server stops. Call once from the mod initializer. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			causes.clear();
			falling.clear();
		});
	}

	/** Called when a player's block change at {@code pos} was logged, which may make blocks above it (or the placed block) fall. */
	static void blockChanged(Level level, BlockPos pos, Lineage.Responsible player) {
		causes.put(level, pos, player);
	}

	/** Called after the block {@code oldState} at {@code pos} turned into a falling block entity. */
	public static void fell(Level level, BlockPos pos, BlockState oldState, FallingBlockEntity entity) {
		Lineage.Responsible cause = causes.get(level, pos.below());
		if (cause == null) {
			cause = causes.get(level, pos);
		}
		if (cause != null) {
			causes.put(level, pos, cause); // the block above falls next
			falling.put(entity, cause);
		}
		BlockCapture.log(ActionType.BLOCK_BREAK, FALLING_SOURCE, cause, level, pos, oldState, null, level.getBlockState(pos), null);
	}

	/** Called after a falling block entity landed, turning {@code oldState} at {@code pos} into a block. */
	public static void landed(FallingBlockEntity entity, Level level, BlockPos pos, BlockState oldState) {
		BlockCapture.log(ActionType.BLOCK_PLACE, FALLING_SOURCE, falling.remove(entity), level, pos, oldState, null,
				level.getBlockState(pos), null);
	}
}
