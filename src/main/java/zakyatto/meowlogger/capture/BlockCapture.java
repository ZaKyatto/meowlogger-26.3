package zakyatto.meowlogger.capture;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * Turns block changes into queued {@link ActionRecord}s: player breaks and placements, buckets,
 * explosions and fire. Called from Fabric events and from the mixins in
 * {@code zakyatto.meowlogger.mixin}. Server thread only.
 */
public final class BlockCapture {
	/** Fire burning a block away. */
	public static final String FIRE_SOURCE = "minecraft:fire";

	/**
	 * Blocks captured before a player break. Container contents must be saved before the break, since
	 * they are dropped during it.
	 */
	public record BreakSnapshot(BlockPos pos, BlockState state, CompoundTag data, BlockPos companion, BlockState companionState) {
	}

	/** State captured when {@code BlockItem.placeBlock} succeeds, logged once {@code BlockItem.place} finishes. */
	private record PendingPlacement(Player player, Level level, BlockPos pos, BlockState oldState,
			BlockPos companion, BlockState companionOldState) {
	}

	/** Block states (and block entity data) of exploded positions before the explosion. */
	public record Snapshot(List<BlockPos> positions, List<BlockState> states, List<CompoundTag> data) {
	}

	private static PendingPlacement pendingPlacement;

	private BlockCapture() {
	}

	/** Called before a player breaks the block at {@code pos}. */
	public static BreakSnapshot beforeBreak(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockPos companion = companion(state, pos);
		return new BreakSnapshot(pos.immutable(), state, state.hasBlockEntity() ? save(level, level.getBlockEntity(pos)) : null,
				companion, companion == null ? null : level.getBlockState(companion));
	}

	/**
	 * Called after a player's break attempt, whether or not it succeeded. Compares instead of trusting
	 * the outcome: breaking a bed's foot in creative removes the head first, which takes the foot with
	 * it, so vanilla (and Fabric's after-break event) never see the foot itself being removed.
	 */
	public static void afterBreak(Player player, Level level, BreakSnapshot snapshot) {
		logIfChanged(ActionType.BLOCK_BREAK, ActionRecord.PLAYER_SOURCE, player, level, snapshot.pos(), snapshot.state(), snapshot.data());
		if (snapshot.companion() != null) {
			logIfChanged(ActionType.BLOCK_BREAK, ActionRecord.PLAYER_SOURCE, player, level,
					snapshot.companion(), snapshot.companionState(), null);
		}
	}

	/** Called when a player's {@code BlockItem.placeBlock} succeeded; completed by {@link #finishPlacement}. */
	public static void beginPlacement(Player player, Level level, BlockPos pos, BlockState oldState, BlockState placedState,
			BlockState companionOldState) {
		pendingPlacement = new PendingPlacement(player, level, pos.immutable(), oldState, companion(placedState, pos), companionOldState);
	}

	/**
	 * Called when {@code BlockItem.place} returns. Logs the pending placement now that the item's block
	 * entity data is applied and the second half of doors and beds exists.
	 */
	public static void finishPlacement() {
		PendingPlacement pending = pendingPlacement;
		pendingPlacement = null;
		if (pending == null) {
			return;
		}
		Level level = pending.level();
		log(ActionType.BLOCK_PLACE, ActionRecord.PLAYER_SOURCE, pending.player(), level, pending.pos(),
				pending.oldState(), null, level.getBlockState(pending.pos()), save(level, level.getBlockEntity(pending.pos())));
		if (pending.companion() != null) {
			logIfChanged(ActionType.BLOCK_PLACE, ActionRecord.PLAYER_SOURCE, pending.player(), level,
					pending.companion(), pending.companionOldState(), null);
		}
	}

	/** The position of the other half of a two-block block (doors, tall plants, beds), or null. */
	public static BlockPos companion(BlockState state, BlockPos pos) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
		}
		if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			Direction towardsHead = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			return pos.relative(state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? towardsHead : towardsHead.getOpposite());
		}
		return null;
	}

	/** Logs a block a player changed with something other than a block item, e.g. a bucket. */
	public static void logPlayerChange(ActionType type, Player player, Level level, BlockPos pos, BlockState oldState, BlockState newState) {
		log(type, ActionRecord.PLAYER_SOURCE, player, level, pos, oldState, null, newState, null);
	}

	/** Logs a block that fire burned away or replaced with fire. */
	public static void logBurn(Level level, BlockPos pos, BlockState oldState, CompoundTag oldData) {
		logIfChanged(ActionType.BLOCK_BREAK, FIRE_SOURCE, null, level, pos, oldState, oldData);
	}

	/** Captures the blocks an explosion is about to destroy. */
	public static Snapshot snapshot(Level level, List<BlockPos> positions) {
		List<BlockPos> kept = new ArrayList<>(positions.size());
		List<BlockState> states = new ArrayList<>(positions.size());
		List<CompoundTag> data = new ArrayList<>(positions.size());
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (!state.isAir()) {
				kept.add(pos.immutable());
				states.add(state);
				data.add(state.hasBlockEntity() ? save(level, level.getBlockEntity(pos)) : null);
			}
		}
		return new Snapshot(kept, states, data);
	}

	/**
	 * Logs every snapshotted block the explosion changed. The source is the exploding entity's type
	 * (or the damage type, e.g. for beds in the Nether); the responsible player is whoever lit or
	 * caused it, if a player.
	 */
	public static void logExplosion(ServerExplosion explosion, Snapshot snapshot) {
		Entity direct = explosion.getDirectSourceEntity();
		String source = direct != null
				? EntityType.getKey(direct.getType()).toString()
				: explosion.getDamageSource().typeHolder().getRegisteredName();
		LivingEntity indirect = explosion.getIndirectSourceEntity();
		Player player = indirect instanceof ServerPlayer serverPlayer ? serverPlayer : null;
		for (int i = 0; i < snapshot.positions().size(); i++) {
			logIfChanged(ActionType.BLOCK_BREAK, source, player, explosion.level(), snapshot.positions().get(i),
					snapshot.states().get(i), snapshot.data().get(i));
		}
	}

	/** Saves a block entity's data, or returns null if there is none. */
	public static CompoundTag save(Level level, BlockEntity blockEntity) {
		return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
	}

	private static void logIfChanged(ActionType type, String source, Player player, Level level, BlockPos pos,
			BlockState oldState, CompoundTag oldData) {
		BlockState newState = level.getBlockState(pos);
		if (newState != oldState) {
			log(type, source, player, level, pos, oldState, oldData, newState,
					newState.hasBlockEntity() ? save(level, level.getBlockEntity(pos)) : null);
		}
	}

	private static void log(ActionType type, String source, Player player, Level level, BlockPos pos,
			BlockState oldState, CompoundTag oldData, BlockState newState, CompoundTag newData) {
		MeowLogger.log(new ActionRecord(System.currentTimeMillis(), type, source,
				player == null ? null : player.getUUID(), player == null ? null : player.getGameProfile().name(),
				level.dimension(), pos, oldState, oldData, newState, newData, null));
	}
}
