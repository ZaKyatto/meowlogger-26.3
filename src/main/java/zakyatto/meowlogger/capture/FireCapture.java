package zakyatto.meowlogger.capture;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
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
	private record Igniter(UUID id, String name) {
	}

	/** Above this many remembered fires in one world, entries for fires that have gone out are dropped. */
	private static final int PRUNE_THRESHOLD = 4_096;

	private static final Map<ResourceKey<Level>, Long2ObjectMap<Igniter>> igniters = new HashMap<>();
	private static Igniter ticking;
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
		Igniter igniter;
		if (player != null) {
			igniter = new Igniter(player.getUUID(), player.getGameProfile().name());
			BlockCapture.log(ActionType.BLOCK_PLACE, ActionRecord.PLAYER_SOURCE, player, level, pos, oldState, null,
					level.getBlockState(pos), null);
		} else if (inFireTick) {
			igniter = ticking; // spread from the fire ticking right now
			if (oldState.isAir()) {
				BlockCapture.logSpread(level, pos, oldState, igniter == null ? null : igniter.id(), igniter == null ? null : igniter.name());
			}
		} else {
			igniter = null; // lava, lightning or a dispenser
		}
		if (igniter != null) {
			Long2ObjectMap<Igniter> fires = igniters.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>());
			if (fires.size() >= PRUNE_THRESHOLD) {
				fires.long2ObjectEntrySet().removeIf(entry -> !(level.getBlockState(BlockPos.of(entry.getLongKey())).getBlock() instanceof BaseFireBlock));
			}
			fires.put(pos.asLong(), igniter);
		}
	}

	/** Called before the fire at {@code pos} ticks (burns and spreads). */
	public static void beginTick(Level level, BlockPos pos) {
		Long2ObjectMap<Igniter> fires = igniters.get(level.dimension());
		ticking = fires == null ? null : fires.get(pos.asLong());
		inFireTick = true;
	}

	/** Called after the fire at {@code pos} ticked; forgets it if it went out. */
	public static void endTick(Level level, BlockPos pos) {
		ticking = null;
		inFireTick = false;
		Long2ObjectMap<Igniter> fires = igniters.get(level.dimension());
		if (fires != null && !(level.getBlockState(pos).getBlock() instanceof BaseFireBlock)) {
			fires.remove(pos.asLong());
		}
	}

	/** Logs a block the currently ticking fire burned, crediting whoever lit that fire. */
	public static void logBurn(Level level, BlockPos pos, BlockState oldState, CompoundTag oldData) {
		Igniter igniter = ticking;
		BlockCapture.logBurn(level, pos, oldState, oldData, igniter == null ? null : igniter.id(), igniter == null ? null : igniter.name());
	}
}
