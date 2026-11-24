package zakyatto.meowlogger.capture;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * Credits fires to the player who lit them. Lighting a fire (flint and steel, fire charges) is
 * logged as a placement; every fire block that spreads from it remembers the same player, so the
 * blocks it burns are logged as caused by them. Server thread only.
 */
public final class FireCapture {
	private static final Lineage igniters = new Lineage((level, pos) -> level.getBlockState(pos).getBlock() instanceof BaseFireBlock, 0);
	private static Lineage.Responsible ticking;
	private static boolean inFireTick;

	private FireCapture() {
	}

	/** Registers cleanup when the server stops. Call once from the mod initializer. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			igniters.clear();
			ticking = null;
			inFireTick = false;
		});
	}

	/**
	 * Called when a fire block appeared at {@code pos}, replacing {@code oldState}. Logs it if a player
	 * lit it, or if it spread into an empty space from a ticking fire (a rollback must remove it, or it
	 * would burn the restored blocks again). Fire replacing a burnt block is already logged as the burn.
	 */
	public static void firePlaced(Level level, BlockPos pos, BlockState oldState) {
		ServerPlayer player = InteractionCapture.actingPlayer();
		Lineage.Responsible igniter;
		if (player != null) {
			igniter = Lineage.Responsible.of(player);
			BlockCapture.log(ActionType.BLOCK_PLACE, ActionRecord.PLAYER_SOURCE, player, level, pos, oldState, null,
					level.getBlockState(pos), null);
		} else if (inFireTick) {
			igniter = ticking; // spread from the fire ticking right now
			if (oldState.isAir()) {
				BlockCapture.log(ActionType.BLOCK_PLACE, BlockCapture.FIRE_SOURCE, igniter, level, pos, oldState, null,
						level.getBlockState(pos), null);
			}
		} else {
			igniter = null; // lava, lightning or a dispenser
		}
		if (igniter != null) {
			igniters.put(level, pos, igniter);
		}
	}

	/** Called before the fire at {@code pos} ticks (burns and spreads). */
	public static void beginTick(Level level, BlockPos pos) {
		ticking = igniters.get(level, pos);
		inFireTick = true;
	}

	/** Called after the fire at {@code pos} ticked; forgets it if it went out. */
	public static void endTick(Level level, BlockPos pos) {
		ticking = null;
		inFireTick = false;
		if (!(level.getBlockState(pos).getBlock() instanceof BaseFireBlock)) {
			igniters.remove(level, pos);
		}
	}

	/** Logs a block the currently ticking fire burned, crediting whoever lit that fire. */
	public static void logBurn(Level level, BlockPos pos, BlockState oldState, CompoundTag oldData) {
		BlockState newState = level.getBlockState(pos);
		if (newState != oldState) {
			BlockCapture.log(ActionType.BLOCK_BREAK, BlockCapture.FIRE_SOURCE, ticking, level, pos, oldState, oldData, newState,
					newState.hasBlockEntity() ? BlockCapture.save(level, level.getBlockEntity(pos)) : null);
		}
	}
}
