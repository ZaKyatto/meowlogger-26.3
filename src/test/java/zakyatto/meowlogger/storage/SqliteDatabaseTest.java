package zakyatto.meowlogger.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

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
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

class SqliteDatabaseTest {
	private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void writesNormalizedRowsAndReopens() throws SQLException {
		Path file = dir.resolve("test.sqlite");
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);

		try (SqliteDatabase db = new SqliteDatabase(file)) {
			db.write(List.of(
					record(ActionType.BLOCK_BREAK, Level.OVERWORLD, Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()),
					record(ActionType.BLOCK_PLACE, Level.NETHER, Blocks.AIR.defaultBlockState(), stairs)));
		}
		// Reopening must not re-run the schema and must reuse existing ids.
		try (SqliteDatabase db = new SqliteDatabase(file)) {
			db.write(List.of(record(ActionType.BLOCK_BREAK, Level.OVERWORLD, stairs, Blocks.AIR.defaultBlockState())));
		}

		try (Connection c = new SQLiteConfig().createConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
			assertEquals(3, count(s, "actions"));
			assertEquals(1, count(s, "players"));
			assertEquals(2, count(s, "worlds"));
			assertEquals(3, count(s, "objects"));
			try (ResultSet r = s.executeQuery("""
					SELECT a.type, w.name, a.x, a.y, a.z, o.name, a.old_state, n.name, a.new_state
					FROM actions a
					JOIN worlds w ON w.id = a.world_id
					JOIN objects o ON o.id = a.old_object_id
					JOIN objects n ON n.id = a.new_object_id
					WHERE a.id = 2""")) {
				r.next();
				assertEquals(ActionType.BLOCK_PLACE.id(), r.getInt(1));
				assertEquals("minecraft:the_nether", r.getString(2));
				assertEquals(List.of(1, -64, 3), List.of(r.getInt(3), r.getInt(4), r.getInt(5)));
				assertEquals("minecraft:air", r.getString(6));
				assertNull(r.getString(7));
				assertEquals("minecraft:oak_stairs", r.getString(8));
				assertEquals("[facing=east,half=bottom,shape=straight,waterlogged=false]", r.getString(9));
			}
		}
	}

	@Test
	void refusesNewerSchema() throws SQLException {
		Path file = dir.resolve("newer.sqlite");
		try (Connection c = new SQLiteConfig().createConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
			s.executeUpdate("PRAGMA user_version = 99");
		}
		assertThrows(SQLException.class, () -> new SqliteDatabase(file));
	}

	private static ActionRecord record(ActionType type, net.minecraft.resources.ResourceKey<Level> world, BlockState oldState, BlockState newState) {
		return new ActionRecord(1L, type, PLAYER, "Meow", world, new BlockPos.MutableBlockPos(1, -64, 3), oldState, newState);
	}

	private static int count(Statement s, String table) throws SQLException {
		try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
			r.next();
			return r.getInt(1);
		}
	}
}
