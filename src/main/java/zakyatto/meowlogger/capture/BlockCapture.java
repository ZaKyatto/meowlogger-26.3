package zakyatto.meowlogger.capture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * Turns block changes into queued {@link ActionRecord}s: player breaks and placements (including
 * blocks that pop off with them), buckets, explosions and fire. Called from Fabric events and from the mixins in
 * {@code zakyatto.meowlogger.mixin}. Server thread only.
 */
public final class BlockCapture {
	/** Fire burning a block away. */
	public static final String FIRE_SOURCE = "minecraft:fire";

	/**
	 * Blocks captured before a player break. Container contents must be saved before the break, since
	 * they are dropped during it. {@code nearby} holds the blocks around it that may pop off with it.
	 */
	public record BreakSnapshot(BlockPos pos, BlockState state, CompoundTag data, BlockPos companion, BlockState companionState,
			Snapshot nearby) {
	}

	/** State captured when {@code BlockItem.placeBlock} succeeds, logged once {@code BlockItem.place} finishes. */
	private record PendingPlacement(Player player, Level level, BlockPos pos, BlockState oldState,
			BlockPos companion, BlockState companionOldState) {
	}

	/**
	 * Block states (and block entity data) captured before a change. The first {@code primary} entries
	 * are the blocks being changed directly; the rest are their neighbours, which are only logged if
	 * they turned into a different block (popped off or fell) rather than just changing shape.
	 */
	public record Snapshot(List<BlockPos> positions, List<BlockState> states, List<CompoundTag> data, int primary) {
	}

	/** Neighbours a block may hold up: the six around it, plus two above for the top half of a door on it. */
	private static final BlockPos[] NEARBY = {
			new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
			new BlockPos(0, 0, 1), new BlockPos(0, 0, -1), new BlockPos(0, 2, 0)};

	private static PendingPlacement pendingPlacement;

	private BlockCapture() {
	}

	/** Called before a player breaks the block at {@code pos}. */
	public static BreakSnapshot beforeBreak(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockPos companion = companion(state, pos);
		BlockPos immutable = pos.immutable();
		Set<BlockPos> direct = companion == null ? Set.of(immutable) : Set.of(immutable, companion);
		return new BreakSnapshot(immutable, state, state.hasBlockEntity() ? save(level, level.getBlockEntity(pos)) : null,
				companion, companion == null ? null : level.getBlockState(companion), snapshot(level, List.of(), direct));
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
		logSnapshot(ActionRecord.PLAYER_SOURCE, player == null ? null : player.getUUID(),
				player == null ? null : player.getGameProfile().name(), level, snapshot.nearby());
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

	/**
	 * Logs a block that fire burned away or replaced with fire, crediting whoever lit the fire.
	 *
	 * @param igniterId   UUID of the player who lit the fire this spread from, or null if unknown
	 * @param igniterName that player's name, or null
	 */
	public static void logBurn(Level level, BlockPos pos, BlockState oldState, CompoundTag oldData, UUID igniterId, String igniterName) {
		BlockState newState = level.getBlockState(pos);
		if (newState != oldState) {
			log(ActionType.BLOCK_BREAK, FIRE_SOURCE, igniterId, igniterName, level, pos, oldState, oldData, newState,
					newState.hasBlockEntity() ? save(level, level.getBlockEntity(pos)) : null);
		}
	}

	/** Logs fire spreading into an empty space, crediting whoever lit the fire it spread from. */
	public static void logSpread(Level level, BlockPos pos, BlockState oldState, UUID igniterId, String igniterName) {
		log(ActionType.BLOCK_PLACE, FIRE_SOURCE, igniterId, igniterName, level, pos, oldState, null, level.getBlockState(pos), null);
	}

	/** Captures the blocks an explosion is about to destroy, and the blocks around them that may pop off. */
	public static Snapshot snapshot(Level level, List<BlockPos> positions) {
		return snapshot(level, positions, Set.of());
	}

	/**
	 * Captures {@code positions} (skipping air), then the neighbours of those and of {@code alsoAround}
	 * that are in neither. Neighbours are only captured if they could pop off (not solid full blocks,
	 * air or fire), and their contents are not saved if they are containers, which never pop off.
	 */
	private static Snapshot snapshot(Level level, List<BlockPos> positions, Set<BlockPos> alsoAround) {
		List<BlockPos> kept = new ArrayList<>(positions.size());
		List<BlockState> states = new ArrayList<>(positions.size());
		List<CompoundTag> data = new ArrayList<>(positions.size());
		Set<BlockPos> covered = new HashSet<>(alsoAround);
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			covered.add(pos.immutable());
			if (!state.isAir()) {
				kept.add(pos.immutable());
				states.add(state);
				data.add(state.hasBlockEntity() ? save(level, level.getBlockEntity(pos)) : null);
			}
		}
		int primary = kept.size();
		for (BlockPos center : new ArrayList<>(covered)) {
			for (BlockPos offset : NEARBY) {
				BlockPos pos = center.offset(offset);
				if (!covered.add(pos)) {
					continue;
				}
				BlockState state = level.getBlockState(pos);
				if (state.isAir() || state.getBlock() instanceof BaseFireBlock || state.isCollisionShapeFullBlock(level, pos)) {
					continue;
				}
				BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
				kept.add(pos);
				states.add(state);
				data.add(blockEntity == null || blockEntity instanceof Container ? null : save(level, blockEntity));
			}
		}
		return new Snapshot(kept, states, data, primary);
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
		logSnapshot(source, player == null ? null : player.getUUID(), player == null ? null : player.getGameProfile().name(),
				explosion.level(), snapshot);
	}

	/** Logs every primary snapshot entry that changed, and every neighbour that became a different block. */
	private static void logSnapshot(String source, UUID playerId, String playerName, Level level, Snapshot snapshot) {
		for (int i = 0; i < snapshot.positions().size(); i++) {
			BlockPos pos = snapshot.positions().get(i);
			BlockState oldState = snapshot.states().get(i);
			BlockState newState = level.getBlockState(pos);
			boolean changed = i < snapshot.primary() ? newState != oldState : newState.getBlock() != oldState.getBlock();
			if (changed) {
				log(ActionType.BLOCK_BREAK, source, playerId, playerName, level, pos, oldState, snapshot.data().get(i), newState,
						newState.hasBlockEntity() ? save(level, level.getBlockEntity(pos)) : null);
			}
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

	/** Logs a block change. {@code player} may be null. */
	public static void log(ActionType type, String source, Player player, Level level, BlockPos pos,
			BlockState oldState, CompoundTag oldData, BlockState newState, CompoundTag newData) {
		log(type, source, player == null ? null : player.getUUID(), player == null ? null : player.getGameProfile().name(),
				level, pos, oldState, oldData, newState, newData);
	}

	private static void log(ActionType type, String source, UUID playerId, String playerName, Level level, BlockPos pos,
			BlockState oldState, CompoundTag oldData, BlockState newState, CompoundTag newData) {
		InteractionCapture.markLogged(pos);
		MeowLogger.log(new ActionRecord(System.currentTimeMillis(), type, source, playerId, playerName,
				level.dimension(), pos, oldState, oldData, newState, newData, null));
	}
}
