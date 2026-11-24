package zakyatto.meowlogger.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.query.LoggedAction;
import zakyatto.meowlogger.query.LookupService;

/**
 * Runs lookups for command sources and shows the results in chat. Remembers each source's last
 * lookup so {@code /ml page} can page through it. Server thread only.
 */
public final class Lookups {
	private record Session(ActionFilter filter, Component title) {
	}

	private static final Map<UUID, Session> sessions = new HashMap<>();

	private Lookups() {
	}

	/** Registers cleanup of per-player state. Call once from the mod initializer. */
	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> sessions.remove(handler.player.getUUID()));
	}

	/** Starts a lookup and sends the requested page to {@code source} once it is done. */
	public static void show(CommandSourceStack source, ActionFilter filter, int page, Component title) {
		LookupService service = MeowLogger.lookups();
		if (service == null) {
			source.sendFailure(Component.literal("MeowLogger is not running"));
			return;
		}
		sessions.put(sessionKey(source), new Session(filter, title));
		service.lookup(filter, page)
				.handleAsync((result, error) -> {
					if (error != null) {
						MeowLogger.LOGGER.error("Lookup failed", error);
						source.sendFailure(Component.literal("Lookup failed; see the server log for details"));
					} else {
						send(source, title, result);
					}
					return null;
				}, source.getServer())
				.exceptionally(error -> {
					MeowLogger.LOGGER.error("Failed to show lookup results", error);
					return null;
				});
	}

	/** Shows another page of the source's last lookup. Returns false if it has none. */
	public static boolean page(CommandSourceStack source, int page) {
		Session session = sessions.get(sessionKey(source));
		if (session == null) {
			return false;
		}
		show(source, session.filter(), page, session.title());
		return true;
	}

	private static void send(CommandSourceStack source, Component title, LookupService.Page result) {
		if (result.total() == 0) {
			source.sendSystemMessage(LookupMessages.noResults(title));
			return;
		}
		if (result.actions().isEmpty()) {
			source.sendFailure(Component.literal("Page " + result.page() + " does not exist; there "
					+ (result.pageCount() == 1 ? "is 1 page" : "are " + result.pageCount() + " pages")));
			return;
		}
		long now = System.currentTimeMillis();
		source.sendSystemMessage(LookupMessages.header(title, result.total()));
		for (LoggedAction action : result.actions()) {
			source.sendSystemMessage(LookupMessages.entry(action, now, source.registryAccess()));
		}
		if (result.pageCount() > 1) {
			source.sendSystemMessage(LookupMessages.footer(result.page(), result.pageCount()));
		}
	}

	private static UUID sessionKey(CommandSourceStack source) {
		Entity entity = source.getEntity();
		return entity != null ? entity.getUUID() : Util.NIL_UUID;
	}
}
