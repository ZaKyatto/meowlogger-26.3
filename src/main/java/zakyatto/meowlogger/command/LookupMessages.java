package zakyatto.meowlogger.command;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.action.ItemData;
import zakyatto.meowlogger.query.LoggedAction;
import zakyatto.meowlogger.storage.NbtBlobs;

/** Builds the chat components for lookup results. */
final class LookupMessages {
	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
	private static final String DEFAULT_NAMESPACE = "minecraft:";
	/** Block entity keys holding a single item: lectern book, jukebox disc, decorated pot contents. */
	private static final List<String> SINGLE_ITEM_KEYS = List.of("Book", "RecordItem", "item");

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

	/**
	 * One lookup line.
	 *
	 * @param withButton whether to add a button that rolls back (or restores) just this action
	 */
	static Component entry(LoggedAction action, long nowMillis, HolderLookup.Provider registries, boolean withButton) {
		MutableComponent verb = switch (action.type()) {
			case BLOCK_BREAK -> Component.literal("broke").withStyle(ChatFormatting.RED);
			case BLOCK_PLACE -> Component.literal("placed").withStyle(ChatFormatting.GREEN);
			case ITEM_INSERT -> Component.literal("added").withStyle(ChatFormatting.GREEN);
			case ITEM_REMOVE -> Component.literal("took").withStyle(ChatFormatting.RED);
			case ENTITY_KILL -> Component.literal("killed").withStyle(ChatFormatting.RED);
			case ENTITY_BREAK -> Component.literal("broke").withStyle(ChatFormatting.RED);
			case ENTITY_PLACE -> Component.literal("placed").withStyle(ChatFormatting.GREEN);
			case ENTITY_CHANGE, BLOCK_CHANGE -> Component.literal("changed").withStyle(ChatFormatting.GOLD);
			case ITEM_DROP -> Component.literal("dropped").withStyle(ChatFormatting.GOLD);
			case ITEM_PICKUP -> Component.literal("picked up").withStyle(ChatFormatting.GOLD);
		};
		Component what = switch (action.type()) {
			case BLOCK_BREAK -> block(action.oldObject(), action);
			case BLOCK_PLACE -> block(action.newObject(), action);
			case ITEM_INSERT, ITEM_REMOVE -> Component.empty()
					.append(items(action, registries))
					.append(Component.literal(action.type() == ActionType.ITEM_INSERT ? " to " : " from ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(shortName(action.oldObject())).withStyle(ChatFormatting.YELLOW));
			case BLOCK_CHANGE -> {
				MutableComponent text = Component.empty();
				if (!action.oldObject().equals(action.newObject())) {
					text.append(block(action.oldObject(), action)).append(Component.literal(" → ").withStyle(ChatFormatting.GRAY));
				}
				text.append(block(action.newObject(), action));
				CompoundTag before = decompress(action.oldData(), null);
				CompoundTag after = decompress(action.newData(), null);
				String detail = before == null || after == null ? "" : blockChangeDetail(before, after);
				yield text.append(Component.literal(detail.isEmpty() ? "" : " (" + detail + ")").withStyle(ChatFormatting.GRAY));
			}
			case ITEM_DROP, ITEM_PICKUP -> itemEntity(action, decompress(action.oldData(), action.newData()), registries);
			case ENTITY_KILL, ENTITY_BREAK, ENTITY_PLACE -> entity(action, decompress(action.oldData(), action.newData()), registries);
			case ENTITY_CHANGE -> {
				CompoundTag before = decompress(action.oldData(), null);
				CompoundTag after = decompress(action.newData(), null);
				String detail = before == null || after == null ? "" : changeDetail(before, after);
				yield Component.empty().append(entity(action, after != null ? after : before, registries))
						.append(Component.literal(detail.isEmpty() ? "" : " (" + detail + ")").withStyle(ChatFormatting.GRAY));
			}
		};

		Component when = Component.literal(ago(nowMillis - action.timeMillis())).withStyle(style -> style
				.withColor(ChatFormatting.GRAY)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(TIMESTAMP.format(Instant.ofEpochMilli(action.timeMillis()))
						+ "\nAction #" + action.id()))));
		String teleport = "/ml teleport " + action.world() + " " + action.x() + " " + action.y() + " " + action.z();
		Component position = Component.literal(action.x() + " " + action.y() + " " + action.z()).withStyle(style -> style
				.withColor(ChatFormatting.GRAY)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(teleport))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Teleport here (" + shortName(action.world()) + ")"))));

		MutableComponent description = Component.empty()
				.append(actor(action)).append(" ")
				.append(verb).append(" ")
				.append(what);
		MutableComponent line = Component.empty().append(when).append(" ");
		if (action.rolledBack()) {
			line.append(description.withStyle(style -> style.withStrikethrough(true)))
					.append(Component.literal(" (rolled back)").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			line.append(description);
		}
		line.append(Component.literal(" at ").withStyle(ChatFormatting.GRAY)).append(position);
		return withButton ? line.append(" ").append(undoButton(action)) : line;
	}

	/** [↶] to roll back just this action, or [↷] to restore it if it is rolled back. */
	private static Component undoButton(LoggedAction action) {
		boolean restore = action.rolledBack();
		String command = "/ml " + (restore ? "restore" : "rollback") + " id:" + action.id();
		return Component.literal(restore ? "[↷]" : "[↶]").withStyle(style -> style
				.withColor(restore ? ChatFormatting.GREEN : ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(restore ? "Restore this action" : "Roll back this action"))));
	}

	private static Component block(String object, LoggedAction action) {
		return Component.literal(shortName(object)).withStyle(style -> style
				.withColor(ChatFormatting.YELLOW)
				.withHoverEvent(new HoverEvent.ShowText(Component.empty()
						.append(Component.literal("Before: ").withStyle(ChatFormatting.GRAY))
						.append(withState(action.oldObject(), action.oldState()))
						.append(Component.literal("\nAfter: ").withStyle(ChatFormatting.GRAY))
						.append(withState(action.newObject(), action.newState())))));
	}

	/** "12 × diamond", hovering to show the item's tooltip (enchantments, name) when its data still decodes. */
	private static Component items(LoggedAction action, HolderLookup.Provider registries) {
		Optional<ItemData> data;
		try {
			CompoundTag tag = NbtBlobs.decompress(action.newData());
			data = tag == null ? Optional.empty() : ItemData.decode(tag, registries);
		} catch (IOException e) {
			data = Optional.empty();
		}
		String count = data.map(items -> items.count() + " × ").orElse("? × ");
		HoverEvent hover = data.<HoverEvent>map(items -> new HoverEvent.ShowItem(items.item()))
				.orElseGet(() -> new HoverEvent.ShowText(Component.literal(action.newObject())));
		return Component.literal(count + shortName(action.newObject())).withStyle(style -> style
				.withColor(ChatFormatting.YELLOW)
				.withHoverEvent(hover));
	}

	/**
	 * "cow", or {@code cow "Bessie"} if it was named, hovering to show the entity's tooltip when its
	 * data still decodes.
	 */
	private static Component entity(LoggedAction action, CompoundTag data, HolderLookup.Provider registries) {
		Optional<EntityType<?>> type = Optional.ofNullable(Identifier.tryParse(action.oldObject()))
				.flatMap(BuiltInRegistries.ENTITY_TYPE::getOptional);
		Optional<UUID> uuid = data == null ? Optional.empty() : data.read(Entity.TAG_UUID, UUIDUtil.CODEC);
		Optional<Component> name = data == null ? Optional.empty()
				: data.read(Entity.TAG_CUSTOM_NAME, ComponentSerialization.CODEC, registries.createSerializationContext(NbtOps.INSTANCE));
		HoverEvent hover = type.isPresent() && uuid.isPresent()
				? new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(type.get(), uuid.get(), name))
				: new HoverEvent.ShowText(Component.literal(action.oldObject()));
		MutableComponent text = Component.literal(shortName(action.oldObject()));
		name.ifPresent(custom -> text.append(" \"").append(custom).append("\""));
		return text.withStyle(style -> style.withColor(ChatFormatting.YELLOW).withHoverEvent(hover));
	}

	/** "5 × diamond" for a dropped or picked-up item entity, hovering to show the item's tooltip. */
	private static Component itemEntity(LoggedAction action, CompoundTag data, HolderLookup.Provider registries) {
		ItemStack stack = data == null ? ItemStack.EMPTY : data.read("Item", ItemStack.CODEC,
				registries.createSerializationContext(NbtOps.INSTANCE)).orElse(ItemStack.EMPTY);
		if (stack.isEmpty()) {
			return Component.literal("? × " + shortName(action.oldObject())).withStyle(ChatFormatting.YELLOW);
		}
		ItemStackTemplate item = ItemStackTemplate.fromNonEmptyStack(stack, 1);
		return Component.literal(stack.getCount() + " × " + shortName(action.oldObject())).withStyle(style -> style
				.withColor(ChatFormatting.YELLOW)
				.withHoverEvent(new HoverEvent.ShowItem(item)));
	}

	/** Decompresses {@code data}, or {@code fallback} if it is null; returns null if neither decodes. */
	private static CompoundTag decompress(byte[] data, byte[] fallback) {
		try {
			CompoundTag tag = NbtBlobs.decompress(data);
			return tag != null ? tag : NbtBlobs.decompress(fallback);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Describes what changed between two saves of an item frame or armor stand: {@code diamond → nothing},
	 * {@code head: nothing → iron_helmet}, or {@code rotated}. Empty if none of those changed.
	 */
	static String changeDetail(CompoundTag before, CompoundTag after) {
		List<String> parts = new ArrayList<>();
		String oldItem = itemName(before.getCompoundOrEmpty("Item"));
		String newItem = itemName(after.getCompoundOrEmpty("Item"));
		if (!oldItem.equals(newItem)) {
			parts.add(oldItem + " → " + newItem);
		}
		CompoundTag oldEquipment = before.getCompoundOrEmpty("equipment");
		CompoundTag newEquipment = after.getCompoundOrEmpty("equipment");
		Set<String> slots = new TreeSet<>(oldEquipment.keySet());
		slots.addAll(newEquipment.keySet());
		for (String slot : slots) {
			String oldSlot = itemName(oldEquipment.getCompoundOrEmpty(slot));
			String newSlot = itemName(newEquipment.getCompoundOrEmpty(slot));
			if (!oldSlot.equals(newSlot)) {
				parts.add(slot + ": " + oldSlot + " → " + newSlot);
			}
		}
		if (parts.isEmpty() && before.getByteOr("ItemRotation", (byte) 0) != after.getByteOr("ItemRotation", (byte) 0)) {
			parts.add("rotated");
		}
		return String.join(", ", parts);
	}

	/**
	 * Describes what changed inside a block between two saves of its block entity: a lectern's book,
	 * a jukebox's disc or a pot's item ({@code nothing → writable_book}), or the number of items in
	 * a chiseled bookshelf or campfire ({@code 2 → 3 items}). Empty if none of those changed.
	 */
	static String blockChangeDetail(CompoundTag before, CompoundTag after) {
		List<String> parts = new ArrayList<>();
		for (String key : SINGLE_ITEM_KEYS) {
			String oldItem = itemName(before.getCompoundOrEmpty(key));
			String newItem = itemName(after.getCompoundOrEmpty(key));
			if (!oldItem.equals(newItem)) {
				parts.add(oldItem + " → " + newItem);
			}
		}
		int oldCount = before.getListOrEmpty("Items").size();
		int newCount = after.getListOrEmpty("Items").size();
		if (oldCount != newCount) {
			parts.add(oldCount + " → " + newCount + " items");
		}
		return String.join(", ", parts);
	}

	private static String itemName(CompoundTag item) {
		return item.getString("id").map(LookupMessages::shortName).orElse("nothing");
	}

	/** "Steve" for a direct player action, "Steve (tnt)" for one a player caused indirectly, "creeper" for no player. */
	static Component actor(LoggedAction action) {
		boolean direct = ActionRecord.PLAYER_SOURCE.equals(action.source());
		if (action.playerName() == null) {
			return Component.literal(shortName(action.source())).withStyle(ChatFormatting.LIGHT_PURPLE);
		}
		MutableComponent actor = Component.literal(action.playerName()).withStyle(ChatFormatting.AQUA);
		if (!direct) {
			actor.append(Component.literal(" (" + shortName(action.source()) + ")").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		return actor;
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
