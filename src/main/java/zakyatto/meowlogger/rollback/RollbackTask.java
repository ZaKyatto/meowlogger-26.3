package zakyatto.meowlogger.rollback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import zakyatto.meowlogger.action.ActionType;

/**
 * Applies a list of parsed actions on the server thread, a time-limited slice per tick, in an order
 * that puts supporting blocks back first (see {@link #applyOrder}). Block changes that conflict with
 * later actions were already left out, so every block change here is applied, overwriting unlogged
 * changes such as grass spreading or water flowing in.
 */
final class RollbackTask {
	// Neighbour and shape updates keep connections (fences, doors) consistent; suppressing drops avoids duplicating items.
	private static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;

	private final CommandSourceStack source;
	private final String parameters;
	private final boolean restore;
	private final List<ParsedAction> changes;
	private final LongArrayList applied;
	private final Map<ActionType, Integer> appliedByType = new EnumMap<>(ActionType.class);
	private final int conflicts;
	private final long startNanos = System.nanoTime();
	private int next;
	private int invalid;
	private int unappliedItems;
	private int unappliedEntities;

	/**
	 * @param changes   changes in the order to apply them: newest first for a rollback, oldest first for a restore
	 * @param conflicts number of matching actions left out because of later conflicting actions
	 * @param invalid   number of matching actions left out because they could not be parsed
	 */
	RollbackTask(CommandSourceStack source, String parameters, boolean restore, List<ParsedAction> changes, int conflicts, int invalid) {
		this.source = source;
		this.parameters = parameters;
		this.restore = restore;
		this.changes = applyOrder(changes, restore);
		this.applied = new LongArrayList(changes.size());
		this.conflicts = conflicts;
		this.invalid = invalid;
	}

	/** A block position in a specific world. */
	private record Location(ResourceKey<Level> world, BlockPos pos) {
	}

	/**
	 * Reorders changes so that what holds something up is back before the thing it holds. The log
	 * order alone isn't enough: an explosion logs all its blocks at once in no particular order, and a
	 * rail or sand block put back before the block under it pops off or falls at the next update.
	 * Returns, in order: positions whose target blocks are solid full blocks or air (in the given
	 * order), positions with a block that needs support (bottom to top), then entity actions (so
	 * minecarts and item frames come back onto their rails and walls). All changes at one position
	 * stay together in their given order, so container contents still follow their container.
	 *
	 * @param changes changes in log order: newest first for a rollback, oldest first for a restore
	 */
	static List<ParsedAction> applyOrder(List<ParsedAction> changes, boolean restore) {
		Set<Location> dependent = new HashSet<>();
		for (ParsedAction change : changes) {
			if (change.type().kind() == ActionType.Kind.BLOCK && needsSupport(restore ? change.newState() : change.oldState())) {
				dependent.add(new Location(change.world(), change.pos()));
			}
		}
		List<ParsedAction> ordered = new ArrayList<>(changes.size());
		List<ParsedAction> late = new ArrayList<>();
		List<ParsedAction> entities = new ArrayList<>();
		for (ParsedAction change : changes) {
			if (change.type().kind() == ActionType.Kind.ENTITY) {
				entities.add(change);
			} else if (dependent.contains(new Location(change.world(), change.pos()))) {
				late.add(change);
			} else {
				ordered.add(change);
			}
		}
		late.sort(Comparator.comparingInt(change -> change.pos().getY())); // stable, so a position keeps its order
		ordered.addAll(late);
		ordered.addAll(entities);
		return ordered;
	}

	/** Whether a block may fall, pop off or break when the block it rests on is missing: anything but solid full blocks and air. */
	static boolean needsSupport(BlockState state) {
		return !state.isAir() && (state.getBlock() instanceof FallingBlock
				|| !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
	}

	/** Applies changes until all are done or {@code deadlineNanos} passes. Returns true when finished. */
	boolean run(MinecraftServer server, long deadlineNanos) {
		while (next < changes.size()) {
			if (System.nanoTime() >= deadlineNanos) {
				return false;
			}
			ParsedAction change = changes.get(next++);
			ServerLevel level = server.getLevel(change.world());
			if (level == null) {
				invalid++;
				continue;
			}
			switch (change.type().kind()) {
				case BLOCK -> setBlock(level, change);
				case ITEM -> {
					if (!moveItems(level, change)) {
						unappliedItems++;
						continue;
					}
				}
				case ENTITY -> {
					if (!applyEntity(level, change)) {
						unappliedEntities++;
						continue;
					}
				}
			}
			applied.add(change.id());
			appliedByType.merge(change.type(), 1, Integer::sum);
		}
		return true;
	}

	private void setBlock(ServerLevel level, ParsedAction change) {
		BlockState target = restore ? change.newState() : change.oldState();
		level.setBlock(change.pos(), target, FLAGS);
		CompoundTag data = restore ? change.newData() : change.oldData();
		BlockState placed = level.getBlockState(change.pos());
		if (data != null && placed.hasBlockEntity()) {
			// A fresh block entity rather than loading into the existing one, which would keep anything
			// the data doesn't mention (e.g. a jukebox's disc when restoring it empty).
			BlockEntity blockEntity = BlockEntity.loadStatic(change.pos(), placed, data, level.registryAccess());
			if (blockEntity != null) {
				level.setBlockEntity(blockEntity);
				blockEntity.setChanged();
				level.sendBlockUpdated(change.pos(), placed, placed, Block.UPDATE_CLIENTS);
			}
		}
	}

	/** Undoing a removal or redoing an insertion puts the items back; the other two take them out. */
	private boolean moveItems(ServerLevel level, ParsedAction change) {
		Object holder = change.entity() != null ? find(level, change.entity(), change.oldData()) : level.getBlockEntity(change.pos());
		if (!(holder instanceof Container container)) {
			return false;
		}
		boolean add = (change.type() == ActionType.ITEM_REMOVE) != restore;
		return add ? ItemTransfer.insert(container, change.items()) : ItemTransfer.remove(container, change.items());
	}

	/**
	 * Brings the entity to its state before the action (rollback) or after it (restore): spawns it if
	 * it existed then, removes it if it did not, or reloads its data if it was changed.
	 */
	private boolean applyEntity(ServerLevel level, ParsedAction change) {
		CompoundTag target = restore ? change.newData() : change.oldData();
		CompoundTag current = restore ? change.oldData() : change.newData();
		if (change.type() == ActionType.ENTITY_CHANGE) {
			Entity entity = find(level, change.entity(), current);
			if (entity == null) {
				return false;
			}
			entity.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), target));
			return true;
		}
		if (target != null) {
			return respawn(level, change.entity(), target);
		}
		Entity entity = find(level, change.entity(), current);
		if (entity == null) {
			return false;
		}
		entity.discard(); // no drops
		return true;
	}

	/**
	 * Spawns an entity from saved data, with full health and no fire or effects. Keeps its UUID, so
	 * leads, owners and other references to it still work, and so it can't exist twice.
	 */
	private static boolean respawn(ServerLevel level, EntityType<?> type, CompoundTag data) {
		Entity entity = EntityType.create(type, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(),
				data), level, EntitySpawnReason.LOAD).orElse(null);
		if (entity == null) {
			return false;
		}
		Entity existing = level.getEntity(entity.getUUID());
		if (existing != null) {
			if (existing.isAlive()) {
				return false;
			}
			existing.discard(); // still playing its death animation
		}
		if (entity instanceof LivingEntity living) {
			living.removeAllEffects();
			living.setHealth(living.getMaxHealth());
			living.deathTime = 0;
			living.hurtTime = 0;
		}
		entity.clearFire();
		entity.setAirSupply(entity.getMaxAirSupply());
		entity.resetFallDistance();
		entity.setDeltaMovement(Vec3.ZERO);
		return level.addFreshEntity(entity);
	}

	/** Finds the loaded entity that {@code data} was saved from, or returns null. */
	private static Entity find(ServerLevel level, EntityType<?> type, CompoundTag data) {
		UUID uuid = data.read(Entity.TAG_UUID, UUIDUtil.CODEC).orElse(null);
		Entity entity = uuid == null ? null : level.getEntity(uuid);
		return entity != null && entity.getType() == type && entity.isAlive() ? entity : null;
	}

	CommandSourceStack source() {
		return source;
	}

	String parameters() {
		return parameters;
	}

	boolean restore() {
		return restore;
	}

	int total() {
		return changes.size() + conflicts + invalid; // unapplied ones are counted within changes
	}

	/** Number of applied actions per type; types with none are left out. */
	Map<ActionType, Integer> appliedByType() {
		return appliedByType;
	}

	int conflicts() {
		return conflicts;
	}

	/** Actions that could not be applied because their world, block or properties no longer exist. */
	int invalid() {
		return invalid;
	}

	/** Item actions that could not be applied: the container is gone, full, or no longer holds the items. */
	int unappliedItems() {
		return unappliedItems;
	}

	/**
	 * Entity actions that could not be applied: the entity to bring back exists already, or the one
	 * to remove or change is gone or not loaded.
	 */
	int unappliedEntities() {
		return unappliedEntities;
	}

	long[] appliedIds() {
		return applied.toLongArray();
	}

	long elapsedMillis() {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
