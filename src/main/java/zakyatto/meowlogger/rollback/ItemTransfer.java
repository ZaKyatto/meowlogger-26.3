package zakyatto.meowlogger.rollback;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import zakyatto.meowlogger.action.ItemData;

/**
 * Puts items into and takes items out of containers for rollbacks. Both operations are
 * all-or-nothing, so a rolled-back action is either fully applied or left untouched and reported.
 */
final class ItemTransfer {
	private ItemTransfer() {
	}

	/** Adds all of {@code items} to {@code container}, topping up matching stacks first. Returns false (changing nothing) if they do not fit. */
	static boolean insert(Container container, ItemData items) {
		ItemStack template = items.stack(1);
		int maxStack = container.getMaxStackSize(template);
		int space = 0;
		for (int slot = 0; slot < container.getContainerSize() && space < items.count(); slot++) {
			ItemStack existing = container.getItem(slot);
			if (existing.isEmpty()) {
				space += container.canPlaceItem(slot, template) ? maxStack : 0;
			} else if (ItemStack.isSameItemSameComponents(existing, template)) {
				space += Math.max(0, maxStack - existing.getCount());
			}
		}
		if (space < items.count()) {
			return false;
		}

		int remaining = items.count();
		for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
			ItemStack existing = container.getItem(slot);
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, template) && existing.getCount() < maxStack) {
				int added = Math.min(remaining, maxStack - existing.getCount());
				existing.grow(added);
				remaining -= added;
			}
		}
		for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
			if (container.getItem(slot).isEmpty() && container.canPlaceItem(slot, template)) {
				int added = Math.min(remaining, maxStack);
				container.setItem(slot, items.stack(added));
				remaining -= added;
			}
		}
		container.setChanged();
		return true;
	}

	/** Removes all of {@code items} from {@code container}. Returns false (changing nothing) if it holds fewer. */
	static boolean remove(Container container, ItemData items) {
		ItemStack template = items.stack(1);
		int available = 0;
		for (int slot = 0; slot < container.getContainerSize() && available < items.count(); slot++) {
			ItemStack existing = container.getItem(slot);
			if (ItemStack.isSameItemSameComponents(existing, template)) {
				available += existing.getCount();
			}
		}
		if (available < items.count()) {
			return false;
		}

		int remaining = items.count();
		for (int slot = container.getContainerSize() - 1; slot >= 0 && remaining > 0; slot--) {
			ItemStack existing = container.getItem(slot);
			if (ItemStack.isSameItemSameComponents(existing, template)) {
				int removed = Math.min(remaining, existing.getCount());
				existing.shrink(removed);
				if (existing.isEmpty()) {
					container.setItem(slot, ItemStack.EMPTY);
				}
				remaining -= removed;
			}
		}
		container.setChanged();
		return true;
	}
}
