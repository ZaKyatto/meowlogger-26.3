package zakyatto.meowlogger.rollback;

import java.util.List;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import zakyatto.meowlogger.action.ActionType;

/**
 * Applies a list of block changes on the server thread, a time-limited slice per tick. Changes that
 * conflict with later actions were already left out, so every change here is applied, overwriting
 * unlogged changes such as grass spreading or water flowing in.
 */
final class RollbackTask {
	// Neighbour and shape updates keep connections (fences, doors) consistent; suppressing drops avoids duplicating items.
	private static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;

	private final CommandSourceStack source;
	private final String parameters;
	private final boolean restore;
	private final List<BlockChange> changes;
	private final LongArrayList applied;
	private final int conflicts;
	private final long startNanos = System.nanoTime();
	private int next;
	private int invalid;
	private int unapplied;

	/**
	 * @param changes   changes in the order to apply them: newest first for a rollback, oldest first for a restore
	 * @param conflicts number of matching actions left out because of later conflicting actions
	 * @param invalid   number of matching actions left out because they could not be parsed
	 */
	RollbackTask(CommandSourceStack source, String parameters, boolean restore, List<BlockChange> changes, int conflicts, int invalid) {
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
			BlockChange change = changes.get(next++);
			ServerLevel level = server.getLevel(change.world());
			if (level == null) {
				invalid++;
				continue;
			}
			if (change.items() != null) {
				if (moveItems(level, change)) {
					applied.add(change.id());
				} else {
					unapplied++;
				}
				continue;
			}
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
			applied.add(change.id());
		}
		return true;
	}

	/** Undoing a removal or redoing an insertion puts the items back; the other two take them out. */
	private boolean moveItems(ServerLevel level, BlockChange change) {
		if (!(level.getBlockEntity(change.pos()) instanceof Container container)) {
			return false;
		}
		boolean add = (change.type() == ActionType.ITEM_REMOVE) != restore;
		return add ? ItemTransfer.insert(container, change.items()) : ItemTransfer.remove(container, change.items());
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

	int conflicts() {
		return conflicts;
	}

	/** Actions that could not be applied because their world, block or properties no longer exist. */
	int invalid() {
		return invalid;
	}

	/** Item actions that could not be applied: the container is gone, full, or no longer holds the items. */
	int unapplied() {
		return unapplied;
	}

	long[] appliedIds() {
		return applied.toLongArray();
	}

	long elapsedMillis() {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
