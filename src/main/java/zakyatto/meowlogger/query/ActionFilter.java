package zakyatto.meowlogger.query;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import zakyatto.meowlogger.action.ActionType;

/**
 * Criteria for looking up logged actions. All criteria are combined with AND; an empty set or null
 * value means "any".
 *
 * @param world       dimension to search, or null for all
 * @param min         inclusive lower corner of the search box, or null for no area limit
 * @param max         inclusive upper corner of the search box; null exactly when {@code min} is null
 * @param players     names of responsible players, matched case-insensitively
 * @param sources     sources such as {@code minecraft:player} or {@code minecraft:creeper}
 * @param types       action types
 * @param objects     block, item or entity identifiers such as {@code minecraft:stone}, matched against the
 *                    old or new object
 * @param sinceMillis  only actions at or after this epoch-millisecond time; 0 for no limit
 * @param beforeMillis only actions before this epoch-millisecond time; 0 for no limit
 * @param rolledBack  only actions that are (true) or are not (false) currently rolled back, or null for both
 * @param ids         only the actions with these ids, e.g. from a lookup line's rollback button
 */
public record ActionFilter(
		ResourceKey<Level> world,
		BlockPos min,
		BlockPos max,
		Set<String> players,
		Set<String> sources,
		Set<ActionType> types,
		Set<String> objects,
		long sinceMillis,
		long beforeMillis,
		Boolean rolledBack,
		Set<Long> ids
) {
	public ActionFilter {
		if ((min == null) != (max == null)) {
			throw new IllegalArgumentException("min and max must both be set or both be null");
		}
		if (min != null) {
			min = min.immutable();
			max = max.immutable();
		}
		players = Set.copyOf(players);
		sources = Set.copyOf(sources);
		types = Set.copyOf(types);
		objects = Set.copyOf(objects);
		ids = Set.copyOf(ids);
	}

	/** Filter for every action at exactly one block. */
	public static ActionFilter at(ResourceKey<Level> world, BlockPos pos) {
		return new ActionFilter(world, pos, pos, Set.of(), Set.of(), Set.of(), Set.of(), 0, 0, null, Set.of());
	}

	/** Copy of this filter restricted to actions that are (or are not) rolled back. */
	public ActionFilter withRolledBack(boolean value) {
		return new ActionFilter(world, min, max, players, sources, types, objects, sinceMillis, beforeMillis, value, ids);
	}

	/** Whether this filter matches every action. */
	public boolean matchesEverything() {
		return world == null && min == null && players.isEmpty() && sources.isEmpty() && types.isEmpty() && objects.isEmpty()
				&& sinceMillis <= 0 && beforeMillis <= 0 && rolledBack == null && ids.isEmpty();
	}

	/** Whether this filter limits the search by area, player or action ids, as rollback and restore require. */
	public boolean isScoped() {
		return min != null || !players.isEmpty() || !ids.isEmpty();
	}
}
