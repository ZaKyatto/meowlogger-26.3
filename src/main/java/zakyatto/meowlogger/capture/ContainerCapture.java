package zakyatto.meowlogger.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.action.ItemData;
import zakyatto.meowlogger.mixin.CompoundContainerAccessor;

/**
 * Logs items players put into or take out of block containers (chests, barrels, shulker boxes,
 * furnaces...). Each menu click is bracketed by a snapshot and a diff, so changes are attributed
 * to exactly the click that caused them; hoppers only run during ticks, never inside a click.
 * Server thread only.
 */
public final class ContainerCapture {
	/** A block container that is part of an open menu, with its contents before a click. */
	public record Tracked(BlockEntity blockEntity, Container container, List<ItemStack> before) {
	}

	private ContainerCapture() {
	}

	/** Captures the contents of every block container in {@code menu}. Returns an empty list if there are none. */
	public static List<Tracked> snapshot(AbstractContainerMenu menu) {
		Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Tracked> tracked = new ArrayList<>(2);
		for (Slot slot : menu.slots) {
			collect(slot.container, seen, tracked);
		}
		return tracked;
	}

	private static void collect(Container container, Set<Container> seen, List<Tracked> tracked) {
		if (!seen.add(container)) {
			return;
		}
		// A double chest is one menu container made of two chest block entities; log each half at its own position.
		if (container instanceof CompoundContainer compound) {
			CompoundContainerAccessor halves = (CompoundContainerAccessor) compound;
			collect(halves.meowlogger$getContainer1(), seen, tracked);
			collect(halves.meowlogger$getContainer2(), seen, tracked);
		} else if (container instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
			tracked.add(new Tracked(blockEntity, container, contents(container)));
		}
	}

	/** Logs the net change of every tracked container since {@link #snapshot}. */
	public static void logChanges(ServerPlayer player, List<Tracked> snapshot) {
		for (Tracked tracked : snapshot) {
			Map<ItemStackTemplate, Integer> changes = diff(tracked.before(), contents(tracked.container()));
			if (changes.isEmpty()) {
				continue;
			}
			BlockEntity blockEntity = tracked.blockEntity();
			Level level = blockEntity.getLevel();
			BlockState state = blockEntity.getBlockState();
			long now = System.currentTimeMillis();
			changes.forEach((item, change) -> MeowLogger.log(new ActionRecord(now,
					change > 0 ? ActionType.ITEM_INSERT : ActionType.ITEM_REMOVE, ActionRecord.PLAYER_SOURCE,
					player.getUUID(), player.getGameProfile().name(), level.dimension(), blockEntity.getBlockPos(),
					state, null, state, new ItemData(item, Math.abs(change)).encode(level.registryAccess()),
					BuiltInRegistries.ITEM.getKey(item.item().value()).toString())));
		}
	}

	/**
	 * Net change per distinct item (same item and components) between two snapshots of the same
	 * container: positive if items were added, negative if removed. Items whose count did not change
	 * overall are left out, so moving a stack between slots logs nothing.
	 */
	static Map<ItemStackTemplate, Integer> diff(List<ItemStack> before, List<ItemStack> after) {
		Map<ItemStackTemplate, Integer> changes = new LinkedHashMap<>();
		for (int i = 0; i < before.size(); i++) {
			ItemStack old = before.get(i);
			ItemStack current = after.get(i);
			if (ItemStack.matches(old, current)) {
				continue;
			}
			if (!old.isEmpty()) {
				changes.merge(ItemStackTemplate.fromNonEmptyStack(old, 1), -old.getCount(), Integer::sum);
			}
			if (!current.isEmpty()) {
				changes.merge(ItemStackTemplate.fromNonEmptyStack(current, 1), current.getCount(), Integer::sum);
			}
		}
		changes.values().removeIf(change -> change == 0);
		return changes;
	}

	private static List<ItemStack> contents(Container container) {
		List<ItemStack> contents = new ArrayList<>(container.getContainerSize());
		for (int i = 0; i < container.getContainerSize(); i++) {
			contents.add(container.getItem(i).copy());
		}
		return contents;
	}
}
