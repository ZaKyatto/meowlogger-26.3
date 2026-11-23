package zakyatto.meowlogger;

import java.nio.file.Path;
import java.sql.SQLException;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.capture.BlockCapture;
import zakyatto.meowlogger.storage.ActionWriter;
import zakyatto.meowlogger.storage.SqliteDatabase;

public class MeowLogger implements ModInitializer {
	public static final String MOD_ID = "meowlogger";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ActionWriter writer;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(MeowLogger::start);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
		BlockCapture.register();
	}

	/** Queues an action for asynchronous storage. Must be called on the server thread. */
	public static void log(ActionRecord record) {
		if (writer != null) {
			writer.submit(record);
		}
	}

	private static void start(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve("meowlogger.sqlite").normalize();
		try {
			writer = new ActionWriter(new SqliteDatabase(file));
		} catch (SQLException e) {
			throw new IllegalStateException("MeowLogger could not open its database at " + file, e);
		}
		LOGGER.info("Logging actions to {}", file);
	}

	private static void stop() {
		if (writer != null) {
			writer.close();
			writer = null;
		}
	}
}
