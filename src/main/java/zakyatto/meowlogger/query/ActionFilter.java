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
 * @param players     player names, matched case-insensitively
 * @param types       action types
 * @param objects     block identifiers such as {@code minecraft:stone}, matched against the old or new block
 * @param sinceMillis only actions at or after this epoch-millisecond time; 0 for no limit
 */
public record ActionFilter(
		ResourceKey<Level> world,
		BlockPos min,
		BlockPos max,
		Set<String> players,
		Set<ActionType> types,
		Set<String> objects,
		long sinceMillis
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
		types = Set.copyOf(types);
		objects = Set.copyOf(objects);
	}

	/** Filter for every action at exactly one block. */
	public static ActionFilter at(ResourceKey<Level> world, BlockPos pos) {
		return new ActionFilter(world, pos, pos, Set.of(), Set.of(), Set.of(), 0);
	}
}
