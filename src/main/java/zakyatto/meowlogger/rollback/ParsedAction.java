package zakyatto.meowlogger.rollback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.action.ItemData;
import zakyatto.meowlogger.query.LoggedAction;
import zakyatto.meowlogger.storage.NbtBlobs;

/**
 * A logged action parsed into game objects, ready to be undone or redone on the server thread.
 *
 * @param oldState for item actions, the container's state; null for entity actions
 * @param oldData  for entity actions, the entity's saved data before, or null if it did not exist
 * @param newData  for entity actions, the entity's saved data after, or null if it no longer exists
 * @param newState for item actions, the container's state; null for entity actions
 * @param items    for item actions, the moved items; otherwise null
 * @param entity   for entity actions, the entity's type; otherwise null
 */
record ParsedAction(long id, ActionType type, ResourceKey<Level> world, BlockPos pos, BlockState oldState, CompoundTag oldData,
		BlockState newState, CompoundTag newData, ItemData items, EntityType<?> entity) {
	/**
	 * Parsed lookup results.
	 *
	 * @param conflicts number of actions left out because a later action by someone else changed the same block
	 * @param invalid   number of actions left out because their world, block, properties, block entity
	 *                  data, item or entity type no longer parse (for example something from a removed mod)
	 */
	record Parsed(List<ParsedAction> changes, int conflicts, int invalid) {
	}

	/**
	 * Parses lookup results, keeping their order and leaving out {@code conflicting} ids. Safe to call
	 * off the server thread, given registries that are not modified concurrently (the server's are frozen).
	 */
	static Parsed parseAll(List<LoggedAction> actions, LongSet conflicting, HolderLookup.Provider registries) {
		Map<String, Optional<BlockState>> states = new HashMap<>();
		Map<String, ResourceKey<Level>> worlds = new HashMap<>();
		List<ParsedAction> changes = new ArrayList<>(actions.size());
		int conflicts = 0;
		int invalid = 0;
		for (LoggedAction action : actions) {
			if (conflicting.contains(action.id())) {
				conflicts++;
				continue;
			}
			ParsedAction change = parse(action, states, worlds, registries);
			if (change == null) {
				invalid++;
			} else {
				changes.add(change);
			}
		}
		return new Parsed(changes, conflicts, invalid);
	}

	private static ParsedAction parse(LoggedAction action, Map<String, Optional<BlockState>> states,
			Map<String, ResourceKey<Level>> worlds, HolderLookup.Provider registries) {
		ResourceKey<Level> world = worlds.computeIfAbsent(action.world(), name -> {
			Identifier id = Identifier.tryParse(name);
			return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
		});
		CompoundTag oldData;
		CompoundTag newData;
		try {
			oldData = NbtBlobs.decompress(action.oldData());
			newData = NbtBlobs.decompress(action.newData());
		} catch (IOException e) {
			return null;
		}
		if (world == null) {
			return null;
		}
		BlockPos pos = new BlockPos(action.x(), action.y(), action.z());
		if (action.type().kind() == ActionType.Kind.ENTITY) {
			Identifier typeId = Identifier.tryParse(action.oldObject());
			Optional<EntityType<?>> type = typeId == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(typeId);
			boolean complete = action.type() == ActionType.ENTITY_CHANGE ? oldData != null && newData != null : oldData != null || newData != null;
			return type.isEmpty() || !complete ? null
					: new ParsedAction(action.id(), action.type(), world, pos, null, oldData, null, newData, null, type.get());
		}
		BlockState oldState = parseState(action.oldObject(), action.oldState(), states);
		if (oldState == null) {
			return null;
		}
		if (action.type().kind() == ActionType.Kind.BLOCK) {
			BlockState newState = parseState(action.newObject(), action.newState(), states);
			return newState == null ? null
					: new ParsedAction(action.id(), action.type(), world, pos, oldState, oldData, newState, newData, null, null);
		}
		// Item actions: new_object is the item, and new_data holds the moved items.
		Optional<ItemData> items = newData == null ? Optional.empty() : ItemData.decode(newData, registries);
		return items.map(moved -> new ParsedAction(action.id(), action.type(), world, pos, oldState, null, oldState, null, moved, null))
				.orElse(null);
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
