package zakyatto.meowlogger.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.query.LoggedAction;
import zakyatto.meowlogger.storage.SqliteDatabaseAccess;

class RollbackTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void parsesStoredStatesBackAndLeavesOutConflicts() {
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		LoggedAction place = action(7, "minecraft:overworld", "minecraft:air", null, "minecraft:oak_stairs", SqliteDatabaseAccess.properties(stairs));
		LoggedAction conflicting = action(11, "minecraft:overworld", "minecraft:stone", null, "minecraft:air", null);
		LoggedAction removedMod = action(8, "minecraft:overworld", "gone:block", null, "minecraft:air", null);
		LoggedAction renamedProperty = action(9, "minecraft:overworld", "minecraft:oak_stairs", "[facing=sideways]", "minecraft:air", null);
		LoggedAction badWorld = action(10, "Not A World", "minecraft:stone", null, "minecraft:air", null);

		BlockChange.Parsed parsed = BlockChange.parseAll(List.of(place, conflicting, removedMod, renamedProperty, badWorld), LongSet.of(11));

		assertEquals(1, parsed.conflicts());
		assertEquals(3, parsed.invalid());
		assertEquals(List.of(new BlockChange(7, Level.OVERWORLD, new BlockPos(1, 2, 3), Blocks.AIR.defaultBlockState(), stairs)), parsed.changes());
		assertNull(BlockChange.parseState("minecraft:stone", "[nope=1]", new HashMap<>()));
	}

	private static LoggedAction action(long id, String world, String oldObject, String oldState, String newObject, String newState) {
		return new LoggedAction(id, 0, ActionType.BLOCK_PLACE, "Meow", world, 1, 2, 3, oldObject, oldState, newObject, newState, false);
	}
}
