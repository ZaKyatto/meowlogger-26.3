package zakyatto.meowlogger.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import zakyatto.meowlogger.TestBootstrap;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.action.ItemData;
import zakyatto.meowlogger.query.LoggedAction;
import zakyatto.meowlogger.storage.SqliteDatabaseAccess;

class RollbackTest {
	private static HolderLookup.Provider REGISTRIES;

	@BeforeAll
	static void bootstrap() {
		REGISTRIES = TestBootstrap.registries();
	}

	@Test
	void parsesStoredStatesAndDataBackAndLeavesOutConflicts() {
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		CompoundTag chest = new CompoundTag();
		chest.putString("id", "minecraft:chest");
		LoggedAction place = action(7, "minecraft:overworld", "minecraft:air", null, null,
				"minecraft:oak_stairs", SqliteDatabaseAccess.properties(stairs), null);
		LoggedAction brokenChest = action(12, "minecraft:overworld", "minecraft:chest", null, SqliteDatabaseAccess.compress(chest),
				"minecraft:air", null, null);
		LoggedAction conflicting = action(11, "minecraft:overworld", "minecraft:stone", null, null, "minecraft:air", null, null);
		LoggedAction removedMod = action(8, "minecraft:overworld", "gone:block", null, null, "minecraft:air", null, null);
		LoggedAction renamedProperty = action(9, "minecraft:overworld", "minecraft:oak_stairs", "[facing=sideways]", null, "minecraft:air", null, null);
		LoggedAction badWorld = action(10, "Not A World", "minecraft:stone", null, null, "minecraft:air", null, null);
		LoggedAction corruptData = action(13, "minecraft:overworld", "minecraft:chest", null, new byte[] {1, 2, 3}, "minecraft:air", null, null);

		ParsedAction.Parsed parsed = ParsedAction.parseAll(
				List.of(place, brokenChest, conflicting, removedMod, renamedProperty, badWorld, corruptData), LongSet.of(11), REGISTRIES);

		assertEquals(1, parsed.conflicts());
		assertEquals(4, parsed.invalid());
		BlockPos pos = new BlockPos(1, 2, 3);
		assertEquals(List.of(
				new ParsedAction(7, ActionType.BLOCK_PLACE, Level.OVERWORLD, pos, Blocks.AIR.defaultBlockState(), null, stairs, null, null, null),
				new ParsedAction(12, ActionType.BLOCK_PLACE, Level.OVERWORLD, pos, Blocks.CHEST.defaultBlockState(), chest,
						Blocks.AIR.defaultBlockState(), null, null, null)),
				parsed.changes());
		assertNull(ParsedAction.parseState("minecraft:stone", "[nope=1]", new HashMap<>()));
	}

	@Test
	void parsesItemActions() {
		ItemData diamonds = new ItemData(new ItemStackTemplate(Items.DIAMOND), 12);
		LoggedAction theft = new LoggedAction(5, 0, ActionType.ITEM_REMOVE, ActionRecord.PLAYER_SOURCE, "Meow", "minecraft:overworld", 1, 2, 3,
				"minecraft:chest", "[facing=north,type=single,waterlogged=false]", null, "minecraft:diamond", null,
				SqliteDatabaseAccess.compress(diamonds.encode(REGISTRIES)), false);
		LoggedAction unknownItem = new LoggedAction(6, 0, ActionType.ITEM_INSERT, ActionRecord.PLAYER_SOURCE, "Meow", "minecraft:overworld", 1, 2, 3,
				"minecraft:chest", null, null, "gone:gizmo", null, SqliteDatabaseAccess.compress(brokenItemData()), false);

		ParsedAction.Parsed parsed = ParsedAction.parseAll(List.of(theft, unknownItem), LongSet.of(), REGISTRIES);

		assertEquals(1, parsed.invalid());
		ParsedAction change = parsed.changes().getFirst();
		assertEquals(ActionType.ITEM_REMOVE, change.type());
		assertEquals(diamonds, change.items());
		assertEquals(Blocks.CHEST, change.oldState().getBlock(), "the old object of an item action is its container");
	}

	@Test
	void parsesEntityKills() {
		CompoundTag cow = new CompoundTag();
		cow.putString("id", "minecraft:cow");
		LoggedAction kill = entityKill(20, "minecraft:cow", SqliteDatabaseAccess.compress(cow));
		LoggedAction removedMod = entityKill(21, "gone:beast", SqliteDatabaseAccess.compress(cow));
		LoggedAction noData = entityKill(22, "minecraft:cow", null);

		ParsedAction.Parsed parsed = ParsedAction.parseAll(List.of(kill, removedMod, noData), LongSet.of(), REGISTRIES);

		assertEquals(2, parsed.invalid());
		assertEquals(List.of(new ParsedAction(20, ActionType.ENTITY_KILL, Level.OVERWORLD, new BlockPos(1, 2, 3), null, cow, null, null, null,
				EntityTypes.COW)), parsed.changes());
	}

	private static LoggedAction entityKill(long id, String type, byte[] data) {
		return new LoggedAction(id, 0, ActionType.ENTITY_KILL, ActionRecord.PLAYER_SOURCE, "Meow", "minecraft:overworld", 1, 2, 3,
				type, null, data, type, null, null, false);
	}

	private static CompoundTag brokenItemData() {
		CompoundTag item = new CompoundTag();
		item.putString("id", "gone:gizmo");
		CompoundTag tag = new CompoundTag();
		tag.put("Item", item);
		tag.putInt("Count", 1);
		return tag;
	}

	private static LoggedAction action(long id, String world, String oldObject, String oldState, byte[] oldData,
			String newObject, String newState, byte[] newData) {
		return new LoggedAction(id, 0, ActionType.BLOCK_PLACE, ActionRecord.PLAYER_SOURCE, "Meow", world, 1, 2, 3,
				oldObject, oldState, oldData, newObject, newState, newData, false);
	}
}
