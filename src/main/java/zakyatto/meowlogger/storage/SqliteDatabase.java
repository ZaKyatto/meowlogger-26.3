package zakyatto.meowlogger.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.sqlite.SQLiteConfig;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.query.LoggedAction;

/**
 * SQLite backend in WAL mode: one connection with manual transactions for writes, and a separate
 * read-only connection so lookups never wait for, or block, the writer.
 */
public final class SqliteDatabase implements Database {
	/** {@code MIGRATIONS[v]} upgrades a database from schema version {@code v} to {@code v + 1}. */
	private static final String[][] MIGRATIONS = {{
			"""
			CREATE TABLE players (
				id   INTEGER PRIMARY KEY,
				uuid TEXT NOT NULL UNIQUE,
				name TEXT NOT NULL
			)""",
			"CREATE TABLE worlds (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
			"CREATE TABLE objects (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
			"""
			CREATE TABLE actions (
				id            INTEGER PRIMARY KEY,
				time          INTEGER NOT NULL,
				type          INTEGER NOT NULL,
				player_id     INTEGER NOT NULL REFERENCES players (id),
				world_id      INTEGER NOT NULL REFERENCES worlds (id),
				x             INTEGER NOT NULL,
				y             INTEGER NOT NULL,
				z             INTEGER NOT NULL,
				old_object_id INTEGER NOT NULL REFERENCES objects (id),
				old_state     TEXT,
				new_object_id INTEGER NOT NULL REFERENCES objects (id),
				new_state     TEXT
			)"""
	}, {
			"CREATE INDEX actions_position ON actions (world_id, x, z, y)",
			"CREATE INDEX actions_player ON actions (player_id, time)",
			"CREATE INDEX actions_time ON actions (time)"
	}};
	private static final int SCHEMA_VERSION = MIGRATIONS.length;

	private static final String LOOKUP_COLUMNS = """
			SELECT a.id, a.time, a.type, p.name, w.name, a.x, a.y, a.z, o.name, a.old_state, n.name, a.new_state
			FROM actions a
			JOIN players p ON p.id = a.player_id
			JOIN worlds w ON w.id = a.world_id
			JOIN objects o ON o.id = a.old_object_id
			JOIN objects n ON n.id = a.new_object_id""";

	private final Connection connection;
	private final Connection readConnection;
	private final PreparedStatement insertAction;
	private final PreparedStatement upsertPlayer;
	private final PreparedStatement upsertWorld;
	private final PreparedStatement upsertObject;

	private final Map<UUID, Integer> playerIds = new HashMap<>();
	private final Map<ResourceKey<Level>, Integer> worldIds = new HashMap<>();
	private final Map<Block, Integer> objectIds = new HashMap<>();

	/**
	 * Opens (creating if needed) the database file and brings its schema up to date.
	 *
	 * @throws SQLException if the file cannot be opened or was written by a newer MeowLogger
	 */
	public SqliteDatabase(Path file) throws SQLException {
		SQLiteConfig config = new SQLiteConfig();
		config.setJournalMode(SQLiteConfig.JournalMode.WAL);
		config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
		String url = "jdbc:sqlite:" + file.toAbsolutePath();
		connection = config.createConnection(url);
		try {
			connection.setAutoCommit(false);
			migrate();
			insertAction = connection.prepareStatement("""
					INSERT INTO actions (time, type, player_id, world_id, x, y, z,
					                     old_object_id, old_state, new_object_id, new_state)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
			upsertPlayer = connection.prepareStatement(
					"INSERT INTO players (uuid, name) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET name = excluded.name RETURNING id");
			upsertWorld = connection.prepareStatement(
					"INSERT INTO worlds (name) VALUES (?) ON CONFLICT (name) DO UPDATE SET name = excluded.name RETURNING id");
			upsertObject = connection.prepareStatement(
					"INSERT INTO objects (name) VALUES (?) ON CONFLICT (name) DO UPDATE SET name = excluded.name RETURNING id");
			// Opened last, so the catch below only ever has the write connection to clean up.
			SQLiteConfig readConfig = new SQLiteConfig();
			readConfig.setReadOnly(true);
			readConfig.setBusyTimeout(5_000);
			readConnection = readConfig.createConnection(url);
		} catch (SQLException e) {
			try {
				connection.close();
			} catch (SQLException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			throw e;
		}
	}

	private void migrate() throws SQLException {
		try (Statement statement = connection.createStatement()) {
			int version;
			try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
				result.next();
				version = result.getInt(1);
			}
			if (version > SCHEMA_VERSION) {
				throw new SQLException("Database schema version " + version
						+ " is newer than this MeowLogger supports (" + SCHEMA_VERSION + "); refusing to open it");
			}
			for (int v = version; v < SCHEMA_VERSION; v++) {
				for (String sql : MIGRATIONS[v]) {
					statement.executeUpdate(sql);
				}
			}
			statement.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
		} catch (SQLException e) {
			connection.rollback();
			throw e;
		}
		connection.commit();
	}

	@Override
	public void write(List<ActionRecord> records) throws SQLException {
		try {
			for (ActionRecord record : records) {
				insertAction.setLong(1, record.timeMillis());
				insertAction.setInt(2, record.type().id());
				insertAction.setInt(3, playerId(record));
				insertAction.setInt(4, worldId(record.world()));
				insertAction.setInt(5, record.pos().getX());
				insertAction.setInt(6, record.pos().getY());
				insertAction.setInt(7, record.pos().getZ());
				insertAction.setInt(8, objectId(record.oldState().getBlock()));
				insertAction.setString(9, properties(record.oldState()));
				insertAction.setInt(10, objectId(record.newState().getBlock()));
				insertAction.setString(11, properties(record.newState()));
				insertAction.addBatch();
			}
			insertAction.executeBatch();
			connection.commit();
		} catch (SQLException | RuntimeException e) {
			// Ids created inside the rolled-back transaction no longer exist.
			playerIds.clear();
			worldIds.clear();
			objectIds.clear();
			try {
				insertAction.clearBatch();
				connection.rollback();
			} catch (SQLException rollbackFailure) {
				e.addSuppressed(rollbackFailure);
			}
			throw e;
		}
	}

	private int playerId(ActionRecord record) throws SQLException {
		Integer id = playerIds.get(record.playerId());
		if (id == null) {
			upsertPlayer.setString(1, record.playerId().toString());
			upsertPlayer.setString(2, record.playerName());
			id = queryId(upsertPlayer);
			playerIds.put(record.playerId(), id);
		}
		return id;
	}

	private int worldId(ResourceKey<Level> world) throws SQLException {
		Integer id = worldIds.get(world);
		if (id == null) {
			upsertWorld.setString(1, world.identifier().toString());
			id = queryId(upsertWorld);
			worldIds.put(world, id);
		}
		return id;
	}

	private int objectId(Block block) throws SQLException {
		Integer id = objectIds.get(block);
		if (id == null) {
			upsertObject.setString(1, BuiltInRegistries.BLOCK.getKey(block).toString());
			id = queryId(upsertObject);
			objectIds.put(block, id);
		}
		return id;
	}

	private static int queryId(PreparedStatement upsert) throws SQLException {
		try (ResultSet result = upsert.executeQuery()) {
			if (!result.next()) {
				throw new SQLException("Upsert returned no id");
			}
			return result.getInt(1);
		}
	}

	/** Returns the property part of a block state, e.g. {@code [facing=north,half=top]}, or null if it has none. */
	static String properties(BlockState state) {
		String serialized = BlockStateParser.serialize(state);
		int start = serialized.indexOf('[');
		return start < 0 ? null : serialized.substring(start);
	}

	@Override
	public int count(ActionFilter filter) throws SQLException {
		Where where = Where.of(filter);
		try (PreparedStatement statement = readConnection.prepareStatement("SELECT COUNT(*) FROM actions a" + where.sql())) {
			where.bind(statement, 1);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		}
	}

	@Override
	public List<LoggedAction> lookup(ActionFilter filter, int offset, int limit) throws SQLException {
		Where where = Where.of(filter);
		String sql = LOOKUP_COLUMNS + where.sql() + " ORDER BY a.id DESC LIMIT ? OFFSET ?";
		try (PreparedStatement statement = readConnection.prepareStatement(sql)) {
			int next = where.bind(statement, 1);
			statement.setInt(next, limit);
			statement.setInt(next + 1, offset);
			List<LoggedAction> actions = new ArrayList<>(limit);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					actions.add(new LoggedAction(result.getLong(1), result.getLong(2), ActionType.fromId(result.getInt(3)),
							result.getString(4), result.getString(5), result.getInt(6), result.getInt(7), result.getInt(8),
							result.getString(9), result.getString(10), result.getString(11), result.getString(12)));
				}
			}
			return actions;
		}
	}

	/** A SQL WHERE clause (empty or starting with a space) plus its positional parameters. */
	record Where(String sql, List<Object> params) {
		static Where of(ActionFilter filter) {
			List<String> clauses = new ArrayList<>();
			List<Object> params = new ArrayList<>();
			if (filter.world() != null) {
				clauses.add("a.world_id = (SELECT id FROM worlds WHERE name = ?)");
				params.add(filter.world().identifier().toString());
			}
			if (filter.min() != null) {
				clauses.add("a.x BETWEEN ? AND ? AND a.z BETWEEN ? AND ? AND a.y BETWEEN ? AND ?");
				params.addAll(List.of(filter.min().getX(), filter.max().getX(), filter.min().getZ(), filter.max().getZ(),
						filter.min().getY(), filter.max().getY()));
			}
			if (!filter.players().isEmpty()) {
				clauses.add("a.player_id IN (SELECT id FROM players WHERE name COLLATE NOCASE IN (" + placeholders(filter.players().size()) + "))");
				params.addAll(filter.players());
			}
			if (!filter.types().isEmpty()) {
				clauses.add("a.type IN (" + placeholders(filter.types().size()) + ")");
				filter.types().forEach(type -> params.add(type.id()));
			}
			if (!filter.objects().isEmpty()) {
				String ids = "(SELECT id FROM objects WHERE name IN (" + placeholders(filter.objects().size()) + "))";
				clauses.add("(a.old_object_id IN " + ids + " OR a.new_object_id IN " + ids + ")");
				params.addAll(filter.objects());
				params.addAll(filter.objects());
			}
			if (filter.sinceMillis() > 0) {
				clauses.add("a.time >= ?");
				params.add(filter.sinceMillis());
			}
			return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
		}

		/** Binds the parameters starting at {@code index} and returns the next free index. */
		int bind(PreparedStatement statement, int index) throws SQLException {
			for (Object param : params) {
				statement.setObject(index++, param);
			}
			return index;
		}

		private static String placeholders(int count) {
			return String.join(", ", Collections.nCopies(count, "?"));
		}
	}

	@Override
	public void close() throws SQLException {
		try (connection; readConnection; Statement statement = connection.createStatement()) {
			// Lets SQLite refresh the statistics its query planner uses to pick indexes.
			statement.execute("PRAGMA optimize");
			connection.commit();
		}
	}
}
