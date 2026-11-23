package zakyatto.meowlogger.rollback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.query.LoggedAction;

/** A logged block change with its states parsed, ready to be applied on the server thread. */
record BlockChange(long id, ResourceKey<Level> world, BlockPos pos, BlockState oldState, BlockState newState) {
	/**
	 * Parsed lookup results.
	 *
	 * @param conflicts number of actions left out because a later action by someone else changed the same block
	 * @param invalid   number of actions left out because their world id, block or properties no
	 *                  longer parse (for example a block from a removed mod)
	 */
	record Parsed(List<BlockChange> changes, int conflicts, int invalid) {
	}

	/** Parses lookup results, keeping their order and leaving out {@code conflicting} ids. Safe to call off the server thread. */
	static Parsed parseAll(List<LoggedAction> actions, LongSet conflicting) {
		Map<String, Optional<BlockState>> states = new HashMap<>();
		Map<String, ResourceKey<Level>> worlds = new HashMap<>();
		List<BlockChange> changes = new ArrayList<>(actions.size());
		int conflicts = 0;
		int invalid = 0;
		for (LoggedAction action : actions) {
			if (conflicting.contains(action.id())) {
				conflicts++;
				continue;
			}
			BlockState oldState = parseState(action.oldObject(), action.oldState(), states);
			BlockState newState = parseState(action.newObject(), action.newState(), states);
			ResourceKey<Level> world = worlds.computeIfAbsent(action.world(), name -> {
				Identifier id = Identifier.tryParse(name);
				return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
			});
			if (oldState == null || newState == null || world == null) {
				invalid++;
				continue;
			}
			changes.add(new BlockChange(action.id(), world, new BlockPos(action.x(), action.y(), action.z()), oldState, newState));
		}
		return new Parsed(changes, conflicts, invalid);
	}

	/** Parses {@code object + properties} (e.g. {@code minecraft:oak_stairs[facing=east]}), or returns null if invalid. */
	static BlockState parseState(String object, String properties, Map<String, Optional<BlockState>> cache) {
		String text = properties == null ? object : object + properties;
		Optional<BlockState> state = cache.get(text);
		if (state == null) {
			try {
				state = Optional.of(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState());
			} catch (CommandSyntaxException e) {
				state = Optional.empty();
			}
			cache.put(text, state);
		}
		return state.orElse(null);
	}
}
