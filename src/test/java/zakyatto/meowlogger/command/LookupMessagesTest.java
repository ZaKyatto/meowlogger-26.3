package zakyatto.meowlogger.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.Test;

class LookupMessagesTest {
	@Test
	void describesItemFrameAndArmorStandChanges() {
		CompoundTag emptyFrame = new CompoundTag();
		CompoundTag diamondFrame = new CompoundTag();
		diamondFrame.put("Item", item("minecraft:diamond"));
		assertEquals("diamond → nothing", LookupMessages.changeDetail(diamondFrame, emptyFrame));

		CompoundTag rotated = diamondFrame.copy();
		rotated.putByte("ItemRotation", (byte) 3);
		assertEquals("rotated", LookupMessages.changeDetail(diamondFrame, rotated));

		CompoundTag bareStand = new CompoundTag();
		CompoundTag dressedStand = new CompoundTag();
		CompoundTag equipment = new CompoundTag();
		equipment.put("head", item("minecraft:iron_helmet"));
		equipment.put("mainhand", item("othermod:wand"));
		dressedStand.put("equipment", equipment);
		assertEquals("head: nothing → iron_helmet, mainhand: nothing → othermod:wand",
				LookupMessages.changeDetail(bareStand, dressedStand));
		assertEquals("", LookupMessages.changeDetail(bareStand, bareStand.copy()));
	}

	@Test
	void describesBlockContentChanges() {
		CompoundTag emptyLectern = new CompoundTag();
		CompoundTag lectern = new CompoundTag();
		lectern.put("Book", item("minecraft:writable_book"));
		assertEquals("nothing → writable_book", LookupMessages.blockChangeDetail(emptyLectern, lectern));

		CompoundTag shelf = new CompoundTag();
		ListTag books = new ListTag();
		books.add(item("minecraft:book"));
		shelf.put("Items", books);
		CompoundTag fullerShelf = shelf.copy();
		fullerShelf.getListOrEmpty("Items").add(item("minecraft:enchanted_book"));
		assertEquals("1 → 2 items", LookupMessages.blockChangeDetail(shelf, fullerShelf));
		assertEquals("", LookupMessages.blockChangeDetail(shelf, shelf.copy()));
	}

	private static CompoundTag item(String id) {
		CompoundTag item = new CompoundTag();
		item.putString("id", id);
		item.putInt("count", 1);
		return item;
	}
}
