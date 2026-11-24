package zakyatto.meowlogger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.capture.EntityCapture;
import zakyatto.meowlogger.capture.FireCapture;
import zakyatto.meowlogger.command.Inspector;
import zakyatto.meowlogger.command.Lookups;
import zakyatto.meowlogger.command.MeowCommands;
import zakyatto.meowlogger.command.Purges;
import zakyatto.meowlogger.config.MeowConfig;
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.query.LookupService;
import zakyatto.meowlogger.rollback.Rollbacks;
import zakyatto.meowlogger.storage.ActionWriter;
import zakyatto.meowlogger.storage.SqliteDatabase;

public class MeowLogger implements ModInitializer {
	public static final String MOD_ID = "meowlogger";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final long RETENTION_INTERVAL_MILLIS = 3_600_000L;
	private static final long DAY_MILLIS = 86_400_000L;

	private static volatile MeowConfig config = MeowConfig.DEFAULTS;
	private static ActionWriter writer;
	private static LookupService lookups;
	private static long nextRetentionRun;
	private static volatile boolean retentionRunning; // cleared on the writer thread

	@Override
	public void onInitialize() {
		try {
			reloadConfig();
		} catch (IOException e) {
			LOGGER.error("Could not load the config, using the defaults: {}", e.getMessage());
		}
		ServerLifecycleEvents.SERVER_STARTING.register(MeowLogger::start);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> applyRetention());
		EntityCapture.register();
		FireCapture.register();
		MeowCommands.register();
		Purges.register();
		Lookups.register();
		Inspector.register();
		Rollbacks.register();
	}

	/** Identifier in the MeowLogger namespace. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** The current config. Safe to call from any thread. */
	public static MeowConfig config() {
		return config;
	}

	/**
	 * Reads {@code config/meowlogger.json} again and applies it. Invalid values fall back to their
	 * defaults; the problems are logged and returned.
	 *
	 * @throws IOException if the file cannot be read or is not valid JSON; the current config stays in effect
	 */
	public static List<String> reloadConfig() throws IOException {
		MeowConfig.Loaded loaded = MeowConfig.load(FabricLoader.getInstance().getConfigDir().resolve("meowlogger.json"));
		loaded.warnings().forEach(warning -> LOGGER.warn("Config: {}", warning));
		config = loaded.config();
		nextRetentionRun = 0; // a changed retention period applies right away
		return loaded.warnings();
	}

	/** Queues an action for asynchronous storage, unless the config ignores it. Must be called on the server thread. */
	public static void log(ActionRecord record) {
		if (writer != null && config.shouldLog(record)) {
			writer.submit(record);
		}
	}

	/** Queues an asynchronous update of the rolled-back flag of stored actions. Server thread only. */
	public static void setRolledBack(long[] ids, boolean rolledBack) {
		if (writer != null) {
			writer.submitRolledBack(ids, rolledBack);
		}
	}

	/**
	 * Permanently deletes the actions matching {@code filter}, asynchronously. The future completes off
	 * the server thread with the number of deleted actions. Server thread only.
	 */
	public static CompletableFuture<Integer> purge(ActionFilter filter) {
		return writer == null ? CompletableFuture.failedFuture(new IllegalStateException("MeowLogger is not running")) : writer.submitPurge(filter);
	}

	/** The lookup service of the running server, or null if no server is running. Server thread only. */
	public static LookupService lookups() {
		return lookups;
	}

	private static void start(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve("meowlogger.sqlite").normalize();
		SqliteDatabase database;
		try {
			database = new SqliteDatabase(file);
		} catch (SQLException e) {
			throw new IllegalStateException("MeowLogger could not open its database at " + file, e);
		}
		writer = new ActionWriter(database);
		lookups = new LookupService(database);
		nextRetentionRun = 0;
		retentionRunning = false;
		LOGGER.info("Logging actions to {}", file);
	}

	private static void applyRetention() {
		long now = System.currentTimeMillis();
		int days = config.retentionDays();
		if (days == 0 || writer == null || retentionRunning || now < nextRetentionRun) {
			return;
		}
		nextRetentionRun = now + RETENTION_INTERVAL_MILLIS;
		retentionRunning = true;
		ActionFilter expired = new ActionFilter(null, null, null, Set.of(), Set.of(), Set.of(), Set.of(), 0, now - days * DAY_MILLIS, null);
		writer.submitPurge(expired).whenComplete((deleted, error) -> {
			retentionRunning = false;
			if (error != null) {
				LOGGER.error("Failed to delete actions older than {} days", days, error);
			} else if (deleted > 0) {
				LOGGER.info("Deleted {} actions older than {} days", deleted, days);
			}
		});
	}

	private static void stop() {
		// Rollbacks queue their final flag updates on the writer; lookups read from the database the writer closes.
		Rollbacks.stop();
		if (lookups != null) {
			lookups.close();
			lookups = null;
		}
		if (writer != null) {
			writer.close();
			writer = null;
		}
	}
}
