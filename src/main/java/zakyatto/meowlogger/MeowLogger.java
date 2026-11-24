package zakyatto.meowlogger;

import java.nio.file.Path;
import java.sql.SQLException;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.command.Inspector;
import zakyatto.meowlogger.command.Lookups;
import zakyatto.meowlogger.command.MeowCommands;
import zakyatto.meowlogger.query.LookupService;
import zakyatto.meowlogger.rollback.Rollbacks;
import zakyatto.meowlogger.storage.ActionWriter;
import zakyatto.meowlogger.storage.SqliteDatabase;

public class MeowLogger implements ModInitializer {
	public static final String MOD_ID = "meowlogger";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ActionWriter writer;
	private static LookupService lookups;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(MeowLogger::start);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
		MeowCommands.register();
		Lookups.register();
		Inspector.register();
		Rollbacks.register();
	}

	/** Identifier in the MeowLogger namespace. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Queues an action for asynchronous storage. Must be called on the server thread. */
	public static void log(ActionRecord record) {
		if (writer != null) {
			writer.submit(record);
		}
	}

	/** Queues an asynchronous update of the rolled-back flag of stored actions. Server thread only. */
	public static void setRolledBack(long[] ids, boolean rolledBack) {
		if (writer != null) {
			writer.submitRolledBack(ids, rolledBack);
		}
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
		LOGGER.info("Logging actions to {}", file);
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
