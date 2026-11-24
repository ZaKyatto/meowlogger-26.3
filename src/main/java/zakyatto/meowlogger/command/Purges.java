package zakyatto.meowlogger.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.query.LookupService;

/**
 * Permanently deletes logged actions in two steps: {@code /ml purge <params>} counts the matches and
 * asks for confirmation, then {@code /ml purge confirm} deletes them. Server thread only.
 */
public final class Purges {
	private static final long CONFIRM_WINDOW_MILLIS = 60_000L;
	private static final String CONFIRM_COMMAND = "/ml purge confirm";

	private record Pending(ActionFilter filter, String parameters, long expiresAtMillis) {
	}

	private static final Map<UUID, Pending> pending = new HashMap<>();
	private static boolean running;

	private Purges() {
	}

	/** Registers cleanup when the server stops. Call once from the mod initializer. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			pending.clear();
			running = false;
		});
	}

	/** Counts the actions matching {@code filter} and asks {@code source} to confirm deleting them. */
	static void request(CommandSourceStack source, ActionFilter filter, String parameters) {
		LookupService lookups = MeowLogger.lookups();
		if (lookups == null) {
			source.sendFailure(Component.literal("MeowLogger is not running"));
			return;
		}
		UUID key = Lookups.sessionKey(source);
		lookups.count(filter).handleAsync((count, error) -> {
			if (error != null) {
				MeowLogger.LOGGER.error("Purge count failed", error);
				source.sendFailure(Component.literal("Lookup failed; see the server log for details"));
			} else if (count == 0) {
				source.sendSystemMessage(prefix().append(Component.literal("Nothing to purge: no matching actions").withStyle(ChatFormatting.GRAY)));
			} else {
				pending.put(key, new Pending(filter, parameters, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS));
				source.sendSystemMessage(prefix()
						.append(Component.literal("This permanently deletes " + count + (count == 1 ? " action" : " actions")
								+ " matching '" + parameters + "'. ").withStyle(ChatFormatting.RED))
						.append(Component.literal("[Confirm]").withStyle(style -> style
								.withColor(ChatFormatting.GOLD)
								.withClickEvent(new ClickEvent.RunCommand(CONFIRM_COMMAND))
								.withHoverEvent(new HoverEvent.ShowText(Component.literal(CONFIRM_COMMAND)))))
						.append(Component.literal(" (expires in " + CONFIRM_WINDOW_MILLIS / 1000 + "s)").withStyle(ChatFormatting.GRAY)));
			}
			return null;
		}, source.getServer()).exceptionally(error -> {
			MeowLogger.LOGGER.error("Failed to ask for purge confirmation", error);
			return null;
		});
	}

	/** Deletes the actions {@code source} last asked to purge, if confirmed in time. */
	static void confirm(CommandSourceStack source) {
		Pending request = pending.remove(Lookups.sessionKey(source));
		if (request == null || System.currentTimeMillis() > request.expiresAtMillis()) {
			source.sendFailure(Component.literal("Nothing to confirm; run /ml purge <parameters> first"));
			return;
		}
		if (running) {
			source.sendFailure(Component.literal("Another purge is still running; try again when it finishes"));
			return;
		}
		running = true;
		long start = System.nanoTime();
		source.sendSystemMessage(prefix().append(Component.literal("Purging...").withStyle(ChatFormatting.GRAY)));
		MeowLogger.purge(request.filter()).handleAsync((deleted, error) -> {
			if (!running) {
				return null; // the server stopped in the meantime
			}
			running = false;
			if (error != null) {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				MeowLogger.LOGGER.error("Purge of '{}' failed", request.parameters(), cause);
				source.sendFailure(Component.literal("Purge failed; see the server log for details"));
				return null;
			}
			MeowLogger.LOGGER.info("{} purged {} actions matching '{}'", source.getTextName(), deleted, request.parameters());
			source.sendSystemMessage(prefix().append(Component.literal("Deleted " + deleted + (deleted == 1 ? " action" : " actions")
					+ " in " + (System.nanoTime() - start) / 1_000_000 / 1000.0 + "s").withStyle(ChatFormatting.GREEN)));
			return null;
		}, source.getServer()).exceptionally(error -> {
			MeowLogger.LOGGER.error("Failed to finish purge", error);
			return null;
		});
	}

	private static MutableComponent prefix() {
		return Component.empty()
				.append(Component.literal("MeowLogger").withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
	}
}
