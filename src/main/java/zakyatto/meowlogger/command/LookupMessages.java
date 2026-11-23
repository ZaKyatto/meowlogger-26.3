package zakyatto.meowlogger.command;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import zakyatto.meowlogger.query.LoggedAction;

/** Builds the chat components for lookup results. */
final class LookupMessages {
	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
	private static final String DEFAULT_NAMESPACE = "minecraft:";

	private LookupMessages() {
	}

	static Component header(Component title, int total) {
		return Component.empty()
				.append(Component.literal("MeowLogger").withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
				.append(title)
				.append(Component.literal(" (" + total + (total == 1 ? " action)" : " actions)")).withStyle(ChatFormatting.GRAY));
	}

	static Component noResults(Component title) {
		return Component.empty()
				.append(Component.literal("MeowLogger").withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
				.append(title)
				.append(Component.literal(": no actions found").withStyle(ChatFormatting.GRAY));
	}

	static Component entry(LoggedAction action, long nowMillis) {
		MutableComponent verb = switch (action.type()) {
			case BLOCK_BREAK -> Component.literal("broke").withStyle(ChatFormatting.RED);
			case BLOCK_PLACE -> Component.literal("placed").withStyle(ChatFormatting.GREEN);
		};
		String object = switch (action.type()) {
			case BLOCK_BREAK -> action.oldObject();
			case BLOCK_PLACE -> action.newObject();
		};

		Component when = Component.literal(ago(nowMillis - action.timeMillis())).withStyle(style -> style
				.withColor(ChatFormatting.GRAY)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(TIMESTAMP.format(Instant.ofEpochMilli(action.timeMillis()))))));
		Component block = Component.literal(shortName(object)).withStyle(style -> style
				.withColor(ChatFormatting.YELLOW)
				.withHoverEvent(new HoverEvent.ShowText(Component.empty()
						.append(Component.literal("Before: ").withStyle(ChatFormatting.GRAY))
						.append(withState(action.oldObject(), action.oldState()))
						.append(Component.literal("\nAfter: ").withStyle(ChatFormatting.GRAY))
						.append(withState(action.newObject(), action.newState())))));
		String teleport = "/ml teleport " + action.world() + " " + action.x() + " " + action.y() + " " + action.z();
		Component position = Component.literal(action.x() + " " + action.y() + " " + action.z()).withStyle(style -> style
				.withColor(ChatFormatting.GRAY)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(teleport))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Teleport here (" + shortName(action.world()) + ")"))));

		MutableComponent description = Component.empty()
				.append(Component.literal(action.playerName()).withStyle(ChatFormatting.AQUA)).append(" ")
				.append(verb).append(" ")
				.append(block);
		MutableComponent line = Component.empty().append(when).append(" ");
		if (action.rolledBack()) {
			line.append(description.withStyle(style -> style.withStrikethrough(true)))
					.append(Component.literal(" (rolled back)").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			line.append(description);
		}
		return line.append(Component.literal(" at ").withStyle(ChatFormatting.GRAY)).append(position);
	}

	static Component footer(int page, int pageCount) {
		return Component.empty()
				.append(pageButton("« Prev", page - 1, page > 1))
				.append(Component.literal("  Page " + page + "/" + pageCount + "  ").withStyle(ChatFormatting.GRAY))
				.append(pageButton("Next »", page + 1, page < pageCount));
	}

	private static Component pageButton(String label, int target, boolean enabled) {
		if (!enabled) {
			return Component.literal(label).withStyle(ChatFormatting.DARK_GRAY);
		}
		return Component.literal(label).withStyle(style -> style
				.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.RunCommand("/ml page " + target))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Go to page " + target))));
	}

	static String ago(long elapsedMillis) {
		long seconds = Math.max(0, elapsedMillis / 1_000);
		if (seconds < 60) {
			return seconds + "s ago";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + "m ago";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + "h ago";
		}
		return hours / 24 + "d ago";
	}

	private static String shortName(String id) {
		return id.startsWith(DEFAULT_NAMESPACE) ? id.substring(DEFAULT_NAMESPACE.length()) : id;
	}

	private static Component withState(String object, String state) {
		return Component.literal(state == null ? object : object + state).withStyle(ChatFormatting.WHITE);
	}
}
