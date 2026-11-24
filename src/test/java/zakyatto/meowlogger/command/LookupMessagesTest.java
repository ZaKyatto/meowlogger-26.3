package zakyatto.meowlogger.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.nbt.CompoundTag;

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

	private static CompoundTag item(String id) {
		CompoundTag item = new CompoundTag();
		item.putString("id", id);
		item.putInt("count", 1);
		return item;
	}
}
