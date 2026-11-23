package zakyatto.meowlogger.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
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
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.query.LoggedAction;

class SqliteDatabaseTest {
	private static final UUID MEOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static BlockState AIR;
	private static final BlockPos ORIGIN = new BlockPos(1, -64, 3);
	private static final ActionFilter ALL = new ActionFilter(null, null, null, Set.of(), Set.of(), Set.of(), 0);

	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		AIR = Blocks.AIR.defaultBlockState();
	}

	@Test
	void writesNormalizedRowsAndReopens() throws SQLException {
		Path file = dir.resolve("test.sqlite");
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);

		try (SqliteDatabase db = new SqliteDatabase(file)) {
			db.write(List.of(
					record(1, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, new BlockPos.MutableBlockPos(1, -64, 3), Blocks.STONE.defaultBlockState(), AIR),
					record(2, ActionType.BLOCK_PLACE, MEOW, "Meow", Level.NETHER, ORIGIN, AIR, stairs)));
		}
		// Reopening must not re-run the schema and must reuse existing ids.
		try (SqliteDatabase db = new SqliteDatabase(file)) {
			db.write(List.of(record(3, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, stairs, AIR)));
		}

		try (Connection c = open(file); Statement s = c.createStatement()) {
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
	void lookupFiltersAndPaginates() throws SQLException {
		BlockState dirt = Blocks.DIRT.defaultBlockState();
		try (SqliteDatabase db = new SqliteDatabase(dir.resolve("lookup.sqlite"))) {
			db.write(List.of(
					record(1_000, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.STONE.defaultBlockState(), AIR),
					record(2_000, ActionType.BLOCK_PLACE, MEOW, "Meow", Level.NETHER, ORIGIN, AIR, Blocks.NETHERRACK.defaultBlockState()),
					record(3_000, ActionType.BLOCK_BREAK, OTHER, "Other", Level.OVERWORLD, new BlockPos(10, 70, 10), dirt, AIR),
					record(4_000, ActionType.BLOCK_PLACE, OTHER, "Other", Level.OVERWORLD, ORIGIN, AIR, dirt)));

			List<LoggedAction> atOrigin = db.lookup(ActionFilter.at(Level.OVERWORLD, ORIGIN), 0, 10);
			assertEquals(List.of(4L, 1L), atOrigin.stream().map(LoggedAction::id).toList(), "newest first, other world excluded");
			LoggedAction place = atOrigin.getFirst();
			assertEquals(new LoggedAction(4, 4_000, ActionType.BLOCK_PLACE, "Other", "minecraft:overworld", 1, -64, 3,
					"minecraft:air", null, "minecraft:dirt", null), place);

			assertEquals(4, db.count(ALL));
			assertEquals(3, db.count(new ActionFilter(Level.OVERWORLD, new BlockPos(0, -70, 0), new BlockPos(12, 80, 12), Set.of(), Set.of(), Set.of(), 0)));
			assertEquals(2, db.count(new ActionFilter(Level.OVERWORLD, new BlockPos(0, -70, 0), new BlockPos(12, 69, 12), Set.of(), Set.of(), Set.of(), 0)), "y is bounded too");
			assertEquals(2, db.count(filter(Set.of("mEoW"), Set.of(), Set.of(), 0)), "player names are case-insensitive");
			assertEquals(4, db.count(filter(Set.of("meow", "other"), Set.of(), Set.of(), 0)));
			assertEquals(0, db.count(filter(Set.of("nobody"), Set.of(), Set.of(), 0)));
			assertEquals(2, db.count(filter(Set.of(), Set.of(ActionType.BLOCK_PLACE), Set.of(), 0)));
			assertEquals(2, db.count(filter(Set.of(), Set.of(), Set.of("minecraft:dirt"), 0)), "matches old and new block");
			assertEquals(2, db.count(filter(Set.of(), Set.of(), Set.of(), 2_500)));
			assertEquals(1, db.count(filter(Set.of("other"), Set.of(ActionType.BLOCK_BREAK), Set.of("minecraft:dirt"), 0)));
			assertEquals(0, db.count(ActionFilter.at(ResourceKey.create(Level.OVERWORLD.registryKey(), Level.OVERWORLD.identifier().withPath("missing")), ORIGIN)));

			assertEquals(List.of(3L, 2L), db.lookup(ALL, 1, 2).stream().map(LoggedAction::id).toList());
			assertEquals(List.of(), db.lookup(ALL, 4, 2));
		}
	}

	@Test
	void positionLookupsUseTheIndex() throws SQLException {
		Path file = dir.resolve("plan.sqlite");
		new SqliteDatabase(file).close();
		SqliteDatabase.Where where = SqliteDatabase.Where.of(new ActionFilter(Level.OVERWORLD, ORIGIN, ORIGIN.offset(5, 5, 5), Set.of(), Set.of(), Set.of(), 0));
		try (Connection c = open(file); PreparedStatement s = c.prepareStatement("EXPLAIN QUERY PLAN SELECT COUNT(*) FROM actions a" + where.sql())) {
			where.bind(s, 1);
			StringBuilder plan = new StringBuilder();
			try (ResultSet r = s.executeQuery()) {
				while (r.next()) {
					plan.append(r.getString("detail")).append('\n');
				}
			}
			assertTrue(plan.toString().contains("actions_position"), plan.toString());
		}
	}

	@Test
	void migratesOlderSchema() throws SQLException {
		Path file = dir.resolve("v1.sqlite");
		new SqliteDatabase(file).close();
		try (Connection c = open(file); Statement s = c.createStatement()) {
			s.executeUpdate("DROP INDEX actions_position");
			s.executeUpdate("DROP INDEX actions_player");
			s.executeUpdate("DROP INDEX actions_time");
			s.executeUpdate("PRAGMA user_version = 1");
		}
		new SqliteDatabase(file).close();
		try (Connection c = open(file); Statement s = c.createStatement()) {
			try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'actions_%'")) {
				r.next();
				assertEquals(3, r.getInt(1));
			}
			try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
				r.next();
				assertEquals(2, r.getInt(1));
			}
		}
	}

	@Test
	void refusesNewerSchema() throws SQLException {
		Path file = dir.resolve("newer.sqlite");
		try (Connection c = open(file); Statement s = c.createStatement()) {
			s.executeUpdate("PRAGMA user_version = 99");
		}
		assertThrows(SQLException.class, () -> new SqliteDatabase(file));
	}

	private static ActionFilter filter(Set<String> players, Set<ActionType> types, Set<String> objects, long since) {
		return new ActionFilter(null, null, null, players, types, objects, since);
	}

	private static ActionRecord record(long time, ActionType type, UUID player, String name, ResourceKey<Level> world,
			BlockPos pos, BlockState oldState, BlockState newState) {
		return new ActionRecord(time, type, player, name, world, pos, oldState, newState);
	}

	private static Connection open(Path file) throws SQLException {
		return new SQLiteConfig().createConnection("jdbc:sqlite:" + file);
	}

	private static int count(Statement s, String table) throws SQLException {
		try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
			r.next();
			return r.getInt(1);
		}
	}
}
