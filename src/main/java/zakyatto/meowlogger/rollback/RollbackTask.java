package zakyatto.meowlogger.rollback;

import java.util.List;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

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
			level.setBlock(change.pos(), restore ? change.newState() : change.oldState(), FLAGS);
			applied.add(change.id());
		}
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
		return changes.size() + conflicts + invalid;
	}

	int conflicts() {
		return conflicts;
	}

	/** Actions that could not be applied because their world, block or properties no longer exist. */
	int invalid() {
		return invalid;
	}

	long[] appliedIds() {
		return applied.toLongArray();
	}

	long elapsedMillis() {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
