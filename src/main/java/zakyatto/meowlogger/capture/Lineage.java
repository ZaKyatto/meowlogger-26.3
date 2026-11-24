package zakyatto.meowlogger.capture;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Remembers which player is responsible for blocks that keep changing on their own after a player
 * started it: fire spreading, fluids flowing, sand falling. Each new block inherits the player of
 * the block it came from. Server thread only.
 */
final class Lineage {
	/** The responsible player. */
	record Responsible(UUID id, String name) {
		static Responsible of(Player player) {
			return new Responsible(player.getUUID(), player.getGameProfile().name());
		}
	}

	private record Entry(Responsible responsible, long gameTime) {
	}

	/** Above this many entries in one world, entries that expired or whose block moved on are dropped. */
	private static final int PRUNE_THRESHOLD = 4_096;

	private final Map<ResourceKey<Level>, Long2ObjectMap<Entry>> entries = new HashMap<>();
	private final BiPredicate<Level, BlockPos> stillActive;
	private final long maxAgeTicks;

	/**
	 * @param stillActive whether the block at a position is still the kind being tracked (e.g. fire); used when pruning
	 * @param maxAgeTicks how long an entry stays valid, or 0 for as long as {@code stillActive} holds
	 */
	Lineage(BiPredicate<Level, BlockPos> stillActive, long maxAgeTicks) {
		this.stillActive = stillActive;
		this.maxAgeTicks = maxAgeTicks;
	}

	void put(Level level, BlockPos pos, Responsible responsible) {
		Long2ObjectMap<Entry> world = entries.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>());
		if (world.size() >= PRUNE_THRESHOLD) {
			world.long2ObjectEntrySet().removeIf(entry -> expired(level, entry.getValue())
					|| !stillActive.test(level, BlockPos.of(entry.getLongKey())));
		}
		world.put(pos.asLong(), new Entry(responsible, level.getGameTime()));
	}

	/** The player responsible for {@code pos}, or null if none or expired. */
	Responsible get(Level level, BlockPos pos) {
		Long2ObjectMap<Entry> world = entries.get(level.dimension());
		Entry entry = world == null ? null : world.get(pos.asLong());
		return entry == null || expired(level, entry) ? null : entry.responsible();
	}

	void remove(Level level, BlockPos pos) {
		Long2ObjectMap<Entry> world = entries.get(level.dimension());
		if (world != null) {
			world.remove(pos.asLong());
		}
	}

	void clear() {
		entries.clear();
	}

	private boolean expired(Level level, Entry entry) {
		return maxAgeTicks > 0 && level.getGameTime() - entry.gameTime() > maxAgeTicks;
	}
}
