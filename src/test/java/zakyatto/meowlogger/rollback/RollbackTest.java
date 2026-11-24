package zakyatto.meowlogger.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	void parsesItemActionsOnEntityContainers() {
		CompoundTag cart = new CompoundTag();
		cart.putString("id", "minecraft:chest_minecart");
		ItemData diamonds = new ItemData(new ItemStackTemplate(Items.DIAMOND), 3);
		LoggedAction theft = new LoggedAction(40, 0, ActionType.ITEM_REMOVE, ActionRecord.PLAYER_SOURCE, "Meow", "minecraft:overworld", 1, 2, 3,
				"minecraft:chest_minecart", null, SqliteDatabaseAccess.compress(cart), "minecraft:diamond", null,
				SqliteDatabaseAccess.compress(diamonds.encode(REGISTRIES)), false);

		ParsedAction change = ParsedAction.parseAll(List.of(theft), LongSet.of(), REGISTRIES).changes().getFirst();

		assertEquals(EntityTypes.CHEST_MINECART, change.entity());
		assertEquals(diamonds, change.items());
		assertNull(change.oldState());
	}

	@Test
	void parsesItemPickupsAsItemEntitiesStoredUnderTheirItem() {
		CompoundTag item = new CompoundTag();
		item.putString("id", "minecraft:item");
		LoggedAction pickup = new LoggedAction(50, 0, ActionType.ITEM_PICKUP, ActionRecord.PLAYER_SOURCE, "Meow", "minecraft:overworld", 1, 2, 3,
				"minecraft:diamond", null, SqliteDatabaseAccess.compress(item), "minecraft:diamond", null, null, false);

		ParsedAction change = ParsedAction.parseAll(List.of(pickup), LongSet.of(), REGISTRIES).changes().getFirst();

		assertEquals(EntityTypes.ITEM, change.entity());
		assertEquals(item, change.oldData());
	}

	@Test
	void parsesEntityPlacementsAndChanges() {
		CompoundTag frame = new CompoundTag();
		frame.putString("id", "minecraft:item_frame");
		byte[] data = SqliteDatabaseAccess.compress(frame);
		LoggedAction place = entityAction(30, ActionType.ENTITY_PLACE, null, data);
		LoggedAction change = entityAction(31, ActionType.ENTITY_CHANGE, data, data);
		LoggedAction halfChange = entityAction(32, ActionType.ENTITY_CHANGE, data, null);

		ParsedAction.Parsed parsed = ParsedAction.parseAll(List.of(place, change, halfChange), LongSet.of(), REGISTRIES);

		assertEquals(1, parsed.invalid(), "a change needs the entity before and after");
		BlockPos pos = new BlockPos(1, 2, 3);
		assertEquals(List.of(
				new ParsedAction(30, ActionType.ENTITY_PLACE, Level.OVERWORLD, pos, null, null, null, frame, null, EntityTypes.ITEM_FRAME),
				new ParsedAction(31, ActionType.ENTITY_CHANGE, Level.OVERWORLD, pos, null, frame, null, frame, null, EntityTypes.ITEM_FRAME)),
				parsed.changes());
	}

	@Test
	void putsSupportsBackBeforeWhatRestsOnThem() {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState rail = Blocks.RAIL.defaultBlockState();
		BlockState sand = Blocks.SAND.defaultBlockState();
		BlockPos ground = new BlockPos(0, 64, 0);
		// An explosion's breaks in the order a rollback would apply them: newest first, in no useful order.
		ParsedAction railBreak = blockChange(1, ground.above(), rail, air);
		ParsedAction upperSand = blockChange(2, ground.above(3), sand, air);
		ParsedAction cartBreak = new ParsedAction(3, ActionType.ENTITY_BREAK, Level.OVERWORLD, ground.above(), null, new CompoundTag(),
				null, null, null, EntityTypes.MINECART);
		ParsedAction lowerSand = blockChange(4, ground.above(2), sand, air);
		ParsedAction groundBreak = blockChange(5, ground, stone, air);
		ParsedAction railPlacedEarlier = blockChange(6, ground.above(), air, rail);

		List<ParsedAction> order = RollbackTask.applyOrder(List.of(railBreak, upperSand, cartBreak, lowerSand, groundBreak, railPlacedEarlier), false);

		assertEquals(List.of(groundBreak, railBreak, railPlacedEarlier, lowerSand, upperSand, cartBreak), order,
				"ground first, then dependent blocks bottom up keeping each position's order, then entities");
		assertFalse(RollbackTask.needsSupport(stone));
		assertFalse(RollbackTask.needsSupport(air));
		assertTrue(RollbackTask.needsSupport(Blocks.TORCH.defaultBlockState()));
		assertTrue(RollbackTask.needsSupport(Blocks.GRAVEL.defaultBlockState()));
	}

	private static ParsedAction blockChange(long id, BlockPos pos, BlockState oldState, BlockState newState) {
		return new ParsedAction(id, ActionType.BLOCK_BREAK, Level.OVERWORLD, pos, oldState, null, newState, null, null, null);
	}

	private static LoggedAction entityAction(long id, ActionType type, byte[] oldData, byte[] newData) {
		return new LoggedAction(id, 0, type, ActionRecord.PLAYER_SOURCE, "Meow", "minecraft:overworld", 1, 2, 3,
				"minecraft:item_frame", null, oldData, "minecraft:item_frame", null, newData, false);
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
