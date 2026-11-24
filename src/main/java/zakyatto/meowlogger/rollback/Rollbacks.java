package zakyatto.meowlogger.rollback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.config.MeowConfig;
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.query.LookupService;

/**
 * Runs rollbacks and restores, one at a time. Matching actions are loaded and parsed off the server
 * thread, then applied on it in time-limited slices per tick (see {@link MeowConfig#rollbackTickBudgetMillis()}).
 * Server thread only.
 */
public final class Rollbacks {
	private static boolean busy;
	private static RollbackTask active;

	private Rollbacks() {
	}

	/** Registers the tick handler. Call once from the mod initializer. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Rollbacks::tick);
	}

	/**
	 * Rolls back (or, if {@code restore}, restores) the actions matching {@code filter}. Rollback only
	 * touches actions that are not rolled back yet; restore only touches rolled-back ones.
	 *
	 * @param parameters the parameters as the user typed them, for messages and the audit log
	 */
	public static void start(CommandSourceStack source, ActionFilter filter, String parameters, boolean restore) {
		LookupService lookups = MeowLogger.lookups();
		if (lookups == null) {
			source.sendFailure(Component.literal("MeowLogger is not running"));
			return;
		}
		if (busy) {
			source.sendFailure(Component.literal("Another rollback or restore is still running; try again when it finishes"));
			return;
		}
		busy = true;
		HolderLookup.Provider registries = source.getServer().registryAccess();
		lookups.lookupForRollback(filter.withRolledBack(restore), MeowLogger.config().maxRollbackActions(),
						(actions, conflicts) -> ParsedAction.parseAll(actions, conflicts, registries))
				.handleAsync((parsed, error) -> {
					begin(source, parameters, restore, parsed, error);
					return null;
				}, source.getServer())
				.exceptionally(error -> {
					busy = false;
					MeowLogger.LOGGER.error("Failed to start rollback", error);
					return null;
				});
	}

	private static void begin(CommandSourceStack source, String parameters, boolean restore, ParsedAction.Parsed parsed, Throwable error) {
		if (!busy) {
			return; // the server stopped in the meantime
		}
		if (error != null) {
			busy = false;
			Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			if (cause instanceof LookupService.TooManyActionsException tooMany) {
				source.sendFailure(Component.literal("More than " + tooMany.limit() + " actions match; narrow it down with radius, time or block"));
			} else {
				MeowLogger.LOGGER.error("Rollback lookup failed", cause);
				source.sendFailure(Component.literal("Lookup failed; see the server log for details"));
			}
			return;
		}
		List<ParsedAction> changes = restore ? parsed.changes().reversed() : parsed.changes();
		RollbackTask task = new RollbackTask(source, parameters, restore, changes, parsed.conflicts(), parsed.invalid());
		if (task.total() == 0) {
			busy = false;
			source.sendSystemMessage(prefix().append(Component.literal(restore
					? "Nothing to restore: no rolled-back actions match"
					: "Nothing to roll back: no matching actions").withStyle(ChatFormatting.GRAY)));
			return;
		}
		source.sendSystemMessage(prefix().append(Component.literal((restore ? "Restoring " : "Rolling back ")
				+ changes.size() + (changes.size() == 1 ? " action..." : " actions...")).withStyle(ChatFormatting.GRAY)));
		active = task;
	}

	private static void tick(MinecraftServer server) {
		if (active != null && active.run(server, System.nanoTime() + MeowLogger.config().rollbackTickBudgetMillis() * 1_000_000L)) {
			RollbackTask task = active;
			active = null;
			busy = false;
			int applied = record(task);
			MeowLogger.LOGGER.info("{} {} {} actions {} ({} conflicting, {} invalid, {} unapplicable skipped) matching '{}'",
					task.source().getTextName(), task.restore() ? "restored" : "rolled back", applied, task.appliedByType(),
					task.conflicts(), task.invalid(), task.unappliedItems() + task.unappliedEntities(), task.parameters());
			task.source().sendSystemMessage(summary(task, applied));
		}
	}

	/** Records the progress of a running task and drops it. Call when the server stops. */
	public static void stop() {
		if (active != null) {
			int applied = record(active);
			MeowLogger.LOGGER.warn("Server stopped during a {}; {} of {} actions were applied",
					active.restore() ? "restore" : "rollback", applied, active.total());
		}
		active = null;
		busy = false;
	}

	private static int record(RollbackTask task) {
		long[] ids = task.appliedIds();
		if (ids.length > 0) {
			MeowLogger.setRolledBack(ids, !task.restore());
		}
		return ids.length;
	}

	private static Component summary(RollbackTask task, int applied) {
		String breakdown = task.appliedByType().entrySet().stream()
				.map(entry -> entry.getKey().key() + ": " + entry.getValue())
				.collect(Collectors.joining("\n"));
		Component message = prefix().append(Component.literal((task.restore() ? "Restored " : "Rolled back ") + applied
				+ (applied == 1 ? " action" : " actions") + " in " + task.elapsedMillis() / 1000.0 + "s").withStyle(style -> style
				.withColor(ChatFormatting.GREEN)
				.withHoverEvent(breakdown.isEmpty() ? null : new HoverEvent.ShowText(Component.literal(breakdown)))));
		int skipped = task.conflicts() + task.invalid() + task.unappliedItems() + task.unappliedEntities();
		if (skipped > 0) {
			List<String> lines = new ArrayList<>(4);
			if (task.conflicts() > 0) {
				lines.add(task.conflicts() + " because someone else changed the block later");
			}
			if (task.invalid() > 0) {
				lines.add(task.invalid() + " because the world, block or item no longer exists");
			}
			if (task.unappliedItems() > 0) {
				lines.add(task.unappliedItems() + " item moves because the container is gone, full, or no longer has the items");
			}
			if (task.unappliedEntities() > 0) {
				lines.add(task.unappliedEntities() + " entity actions because the entity exists already, or is gone or not loaded");
			}
			Component reasons = Component.literal(String.join("\n", lines));
			message = Component.empty().append(message).append(Component.literal(", skipped " + skipped).withStyle(style -> style
					.withColor(ChatFormatting.YELLOW)
					.withHoverEvent(new HoverEvent.ShowText(reasons))));
		}
		String undo = "/ml " + (task.restore() ? "rollback " : "restore ") + task.parameters();
		return Component.empty().append(message).append(" ").append(Component.literal("[Undo]").withStyle(style -> style
				.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.SuggestCommand(undo))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(undo)))));
	}

	private static MutableComponent prefix() {
		return Component.empty()
				.append(Component.literal("MeowLogger").withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
	}
}
