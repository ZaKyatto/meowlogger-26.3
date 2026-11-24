package zakyatto.meowlogger.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
	private static final ActionFilter ALL = new ActionFilter(null, null, null, Set.of(), Set.of(), Set.of(), Set.of(), 0, 0, null);

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
			assertEquals(List.of(4L, 4_000L, ActionType.BLOCK_PLACE, ActionRecord.PLAYER_SOURCE, "Other", "minecraft:overworld",
					1, -64, 3, "minecraft:air", "minecraft:dirt", false),
					List.of(place.id(), place.timeMillis(), place.type(), place.source(), place.playerName(), place.world(),
							place.x(), place.y(), place.z(), place.oldObject(), place.newObject(), place.rolledBack()));
			assertNull(place.oldState());
			assertNull(place.oldData());
			assertNull(place.newData());

			assertEquals(4, db.count(ALL));
			assertEquals(3, db.count(new ActionFilter(Level.OVERWORLD, new BlockPos(0, -70, 0), new BlockPos(12, 80, 12), Set.of(), Set.of(), Set.of(), Set.of(), 0, 0, null)));
			assertEquals(2, db.count(new ActionFilter(Level.OVERWORLD, new BlockPos(0, -70, 0), new BlockPos(12, 69, 12), Set.of(), Set.of(), Set.of(), Set.of(), 0, 0, null)), "y is bounded too");
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
		SqliteDatabase.Where where = SqliteDatabase.Where.of(new ActionFilter(Level.OVERWORLD, ORIGIN, ORIGIN.offset(5, 5, 5), Set.of(), Set.of(), Set.of(), Set.of(), 0, 0, null));
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
	void migratesVersionOneDatabase() throws SQLException {
		Path file = dir.resolve("v1.sqlite");
		try (Connection c = open(file); Statement s = c.createStatement()) {
			s.executeUpdate("CREATE TABLE players (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, name TEXT NOT NULL)");
			s.executeUpdate("CREATE TABLE worlds (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
			s.executeUpdate("CREATE TABLE objects (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
			s.executeUpdate("""
					CREATE TABLE actions (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, type INTEGER NOT NULL,
					player_id INTEGER NOT NULL, world_id INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
					old_object_id INTEGER NOT NULL, old_state TEXT, new_object_id INTEGER NOT NULL, new_state TEXT)""");
			s.executeUpdate("INSERT INTO players VALUES (1, '" + MEOW + "', 'Meow')");
			s.executeUpdate("INSERT INTO worlds VALUES (1, 'minecraft:overworld')");
			s.executeUpdate("INSERT INTO objects VALUES (1, 'minecraft:oak_log'), (2, 'minecraft:air')");
			s.executeUpdate("INSERT INTO actions VALUES (7, 1234, 1, 1, 1, 1, -64, 3, 1, '[axis=x]', 2, NULL)");
			s.executeUpdate("PRAGMA user_version = 1");
		}
		try (SqliteDatabase db = new SqliteDatabase(file)) {
			List<LoggedAction> actions = db.lookup(ActionFilter.at(Level.OVERWORLD, ORIGIN), 0, 10);
			assertEquals(1, actions.size());
			LoggedAction action = actions.getFirst();
			assertEquals(List.of(7L, 1234L, ActionType.BLOCK_BREAK, ActionRecord.PLAYER_SOURCE, "Meow", "minecraft:oak_log", "[axis=x]", false),
					List.of(action.id(), action.timeMillis(), action.type(), action.source(), action.playerName(),
							action.oldObject(), action.oldState(), action.rolledBack()));
			// The migrated table accepts the new kinds of rows.
			db.write(List.of(new ActionRecord(2_000, ActionType.BLOCK_BREAK, "minecraft:creeper", null, null, Level.OVERWORLD, ORIGIN,
					AIR, null, AIR, null, null)));
		}
		try (Connection c = open(file); Statement s = c.createStatement()) {
			try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'actions_%'")) {
				r.next();
				assertEquals(3, r.getInt(1));
			}
			try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
				r.next();
				assertEquals(4, r.getInt(1));
			}
		}
	}

	@Test
	void storesNonPlayerSourcesAndBlockEntityData() throws SQLException {
		CompoundTag chest = new CompoundTag();
		chest.putString("id", "minecraft:chest");
		chest.putString("CustomName", "\"loot\"");
		try (SqliteDatabase db = new SqliteDatabase(dir.resolve("sources.sqlite"))) {
			db.write(List.of(
					new ActionRecord(1, ActionType.BLOCK_BREAK, "minecraft:creeper", null, null, Level.OVERWORLD, ORIGIN,
							Blocks.CHEST.defaultBlockState(), chest, AIR, null, null),
					new ActionRecord(2, ActionType.BLOCK_BREAK, "minecraft:tnt", MEOW, "Meow", Level.OVERWORLD, ORIGIN.above(),
							Blocks.STONE.defaultBlockState(), null, AIR, null, null),
					record(3, ActionType.BLOCK_PLACE, MEOW, "Meow", Level.OVERWORLD, ORIGIN, AIR, Blocks.DIRT.defaultBlockState())));

			LoggedAction creeper = db.lookup(ActionFilter.at(Level.OVERWORLD, ORIGIN), 1, 1).getFirst();
			assertEquals("minecraft:creeper", creeper.source());
			assertNull(creeper.playerName());
			assertEquals(chest, NbtIo.readCompressed(new ByteArrayInputStream(creeper.oldData()), NbtAccounter.unlimitedHeap()));

			assertEquals(List.of(2L), ids(db, sourceFilter("minecraft:tnt")));
			assertEquals(List.of(3L, 2L), ids(db, filter(Set.of("meow"), Set.of(), Set.of(), 0)), "indirectly caused actions count for the player");
			assertEquals(List.of(3L), ids(db, sourceFilter(ActionRecord.PLAYER_SOURCE)));
			assertEquals(3, db.count(ALL), "actions without a player are not lost to the player join");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void deletesMatchingActionsInChunks() throws SQLException {
		try (SqliteDatabase db = new SqliteDatabase(dir.resolve("purge.sqlite"))) {
			db.write(List.of(
					record(1_000, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.STONE.defaultBlockState(), AIR),
					record(2_000, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.STONE.defaultBlockState(), AIR),
					record(3_000, ActionType.BLOCK_BREAK, OTHER, "Other", Level.OVERWORLD, ORIGIN, Blocks.STONE.defaultBlockState(), AIR),
					record(9_000, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.STONE.defaultBlockState(), AIR)));
			ActionFilter oldMeow = new ActionFilter(null, null, null, Set.of("meow"), Set.of(), Set.of(), Set.of(), 0, 5_000, null);

			assertEquals(2, ids(db, oldMeow).size(), "before: excludes actions at or after its time");
			assertEquals(1, db.delete(oldMeow, 1));
			assertEquals(1, db.delete(oldMeow, 1));
			assertEquals(0, db.delete(oldMeow, 1));
			assertEquals(List.of(4L, 3L), ids(db, ALL));
		}
	}

	@Test
	void storesEntityKillsWithoutBlockStatesAndNeverAsConflicts() throws SQLException, IOException {
		CompoundTag cow = new CompoundTag();
		cow.putString("id", "minecraft:cow");
		cow.putFloat("Health", 0);
		try (SqliteDatabase db = new SqliteDatabase(dir.resolve("entities.sqlite"))) {
			db.write(List.of(
					record(1, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.GRASS_BLOCK.defaultBlockState(), AIR),
					new ActionRecord(2, ActionType.ENTITY_KILL, "minecraft:arrow", OTHER, "Other", Level.OVERWORLD, ORIGIN,
							null, cow, null, null, "minecraft:cow")));

			LoggedAction kill = db.lookup(filter(Set.of(), Set.of(), Set.of("minecraft:cow"), 0), 0, 10).getFirst();
			assertEquals(List.of(2L, ActionType.ENTITY_KILL, "minecraft:arrow", "Other", "minecraft:cow", "minecraft:cow"),
					List.of(kill.id(), kill.type(), kill.source(), kill.playerName(), kill.oldObject(), kill.newObject()));
			assertNull(kill.oldState());
			assertNull(kill.newData());
			assertEquals(cow, NbtIo.readCompressed(new ByteArrayInputStream(kill.oldData()), NbtAccounter.unlimitedHeap()));
			assertArrayEquals(new long[0], db.conflicting(filter(Set.of("Meow"), Set.of(), Set.of(), 0).withRolledBack(false)),
					"someone killing a mob where Meow broke a block doesn't stop the break being rolled back");
		}
	}

	@Test
	void findsActionsOverriddenByLaterOutsideActions() throws SQLException {
		BlockPos other = new BlockPos(50, 64, 50);
		try (SqliteDatabase db = new SqliteDatabase(dir.resolve("conflicts.sqlite"))) {
			db.write(List.of(
					record(1, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.STONE.defaultBlockState(), AIR),
					record(2, ActionType.BLOCK_PLACE, OTHER, "Other", Level.OVERWORLD, ORIGIN, AIR, Blocks.DIRT.defaultBlockState()),
					record(3, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, other, Blocks.STONE.defaultBlockState(), AIR),
					record(4, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.NETHER, ORIGIN, Blocks.NETHERRACK.defaultBlockState(), AIR)));
			ActionFilter meow = filter(Set.of("Meow"), Set.of(), Set.of(), 0).withRolledBack(false);

			assertArrayEquals(new long[] {1}, db.conflicting(meow), "Other built on the spot Meow cleared");
			assertArrayEquals(new long[0], db.conflicting(filter(Set.of("Meow", "Other"), Set.of(), Set.of(), 0).withRolledBack(false)),
					"a later action inside the filter is rolled back along with it");

			db.setRolledBack(new long[] {2}, true);
			assertArrayEquals(new long[0], db.conflicting(meow), "a rolled-back later action no longer counts");

			// Restore: Meow's break at 'other' was rolled back, then Other placed a block there.
			db.setRolledBack(new long[] {3}, true);
			db.write(List.of(record(5, ActionType.BLOCK_PLACE, OTHER, "Other", Level.OVERWORLD, other, AIR, Blocks.OAK_PLANKS.defaultBlockState())));
			assertArrayEquals(new long[] {3}, db.conflicting(filter(Set.of("Meow"), Set.of(), Set.of(), 0).withRolledBack(true)));
		}
	}

	@Test
	void storesItemActionsAndNeverCountsThemAsConflicts() throws SQLException {
		try (SqliteDatabase db = new SqliteDatabase(dir.resolve("items.sqlite"))) {
			db.write(List.of(
					itemRecord(1, ActionType.ITEM_REMOVE, MEOW, "Meow", ORIGIN, "minecraft:diamond"),
					itemRecord(2, ActionType.ITEM_INSERT, OTHER, "Other", ORIGIN, "minecraft:dirt"),
					itemRecord(3, ActionType.ITEM_REMOVE, OTHER, "Other", ORIGIN, "minecraft:diamond")));

			LoggedAction theft = db.lookup(ActionFilter.at(Level.OVERWORLD, ORIGIN), 2, 1).getFirst();
			assertEquals(List.of(ActionType.ITEM_REMOVE, "minecraft:chest", "minecraft:diamond"),
					List.of(theft.type(), theft.oldObject(), theft.newObject()));
			assertEquals(List.of(3L, 1L), ids(db, filter(Set.of(), Set.of(), Set.of("minecraft:diamond"), 0)));

			ActionFilter meow = filter(Set.of("Meow"), Set.of(), Set.of(), 0).withRolledBack(false);
			assertArrayEquals(new long[0], db.conflicting(meow), "Other using the chest later must not block undoing Meow's theft");
			// A later block change at the same spot still conflicts with an earlier block change.
			db.write(List.of(
					record(4, ActionType.BLOCK_PLACE, MEOW, "Meow", Level.OVERWORLD, ORIGIN.above(), AIR, Blocks.DIRT.defaultBlockState()),
					record(5, ActionType.BLOCK_BREAK, OTHER, "Other", Level.OVERWORLD, ORIGIN.above(), Blocks.DIRT.defaultBlockState(), AIR)));
			assertArrayEquals(new long[] {4}, db.conflicting(meow));
		}
	}

	@Test
	void tracksRolledBackFlag() throws SQLException {
		try (SqliteDatabase db = new SqliteDatabase(dir.resolve("flags.sqlite"))) {
			db.write(List.of(
					record(1, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.STONE.defaultBlockState(), AIR),
					record(2, ActionType.BLOCK_PLACE, MEOW, "Meow", Level.OVERWORLD, ORIGIN, AIR, Blocks.DIRT.defaultBlockState()),
					record(3, ActionType.BLOCK_BREAK, MEOW, "Meow", Level.OVERWORLD, ORIGIN, Blocks.DIRT.defaultBlockState(), AIR)));
			db.setRolledBack(new long[] {1, 3}, true);

			assertEquals(List.of(3L, 1L), db.lookup(ALL.withRolledBack(true), 0, 10).stream().map(LoggedAction::id).toList());
			assertEquals(List.of(2L), db.lookup(ALL.withRolledBack(false), 0, 10).stream().map(LoggedAction::id).toList());
			assertEquals(List.of(true, false, true), db.lookup(ALL, 0, 10).stream().map(LoggedAction::rolledBack).toList());

			db.setRolledBack(new long[] {3}, false);
			assertEquals(List.of(1L), db.lookup(ALL.withRolledBack(true), 0, 10).stream().map(LoggedAction::id).toList());
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
		return new ActionFilter(null, null, null, players, Set.of(), types, objects, since, 0, null);
	}

	private static ActionRecord record(long time, ActionType type, UUID player, String name, ResourceKey<Level> world,
			BlockPos pos, BlockState oldState, BlockState newState) {
		return new ActionRecord(time, type, ActionRecord.PLAYER_SOURCE, player, name, world, pos, oldState, null, newState, null, null);
	}

	private static ActionRecord itemRecord(long time, ActionType type, UUID player, String name, BlockPos pos, String item) {
		BlockState chest = Blocks.CHEST.defaultBlockState();
		CompoundTag items = new CompoundTag();
		items.putInt("Count", 3);
		return new ActionRecord(time, type, ActionRecord.PLAYER_SOURCE, player, name, Level.OVERWORLD, pos, chest, null, chest, items, item);
	}

	private static ActionFilter sourceFilter(String source) {
		return new ActionFilter(null, null, null, Set.of(), Set.of(source), Set.of(), Set.of(), 0, 0, null);
	}

	private static List<Long> ids(SqliteDatabase db, ActionFilter filter) throws SQLException {
		return db.lookup(filter, 0, 100).stream().map(LoggedAction::id).toList();
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
