package zakyatto.meowlogger.rollback;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import zakyatto.meowlogger.action.ActionType;

/**
 * Applies a list of parsed actions on the server thread, a time-limited slice per tick. Block changes
 * that conflict with later actions were already left out, so every block change here is applied,
 * overwriting unlogged changes such as grass spreading or water flowing in.
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
		this.changes = changes;
		this.applied = new LongArrayList(changes.size());
		this.conflicts = conflicts;
		this.invalid = invalid;
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
					if (!(restore ? killAgain(level, change) : respawn(level, change))) {
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
		if (data != null) {
			BlockEntity blockEntity = level.getBlockEntity(change.pos());
			if (blockEntity != null) {
				blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
				blockEntity.setChanged();
				level.sendBlockUpdated(change.pos(), target, target, Block.UPDATE_CLIENTS);
			}
		}
	}

	/** Undoing a removal or redoing an insertion puts the items back; the other two take them out. */
	private boolean moveItems(ServerLevel level, ParsedAction change) {
		if (!(level.getBlockEntity(change.pos()) instanceof Container container)) {
			return false;
		}
		boolean add = (change.type() == ActionType.ITEM_REMOVE) != restore;
		return add ? ItemTransfer.insert(container, change.items()) : ItemTransfer.remove(container, change.items());
	}

	/**
	 * Brings a killed entity back where it died, with full health and no fire or effects. Keeps its
	 * UUID, so leads, owners and other references to it still work, and so it can't exist twice.
	 */
	private static boolean respawn(ServerLevel level, ParsedAction change) {
		Entity entity = EntityType.create(change.entity(), TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(),
				change.oldData()), level, EntitySpawnReason.LOAD).orElse(null);
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

	/** Removes an entity that a rollback brought back, without drops. Fails if it is not loaded. */
	private static boolean killAgain(ServerLevel level, ParsedAction change) {
		UUID uuid = change.oldData().read(Entity.TAG_UUID, UUIDUtil.CODEC).orElse(null);
		Entity entity = uuid == null ? null : level.getEntity(uuid);
		if (entity == null || entity.getType() != change.entity()) {
			return false;
		}
		entity.discard();
		return true;
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
	 * Entity actions that could not be applied: on rollback the entity is alive already, on restore it
	 * is not loaded or gone.
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
