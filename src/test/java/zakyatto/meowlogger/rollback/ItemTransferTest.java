package zakyatto.meowlogger.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import zakyatto.meowlogger.TestBootstrap;
import zakyatto.meowlogger.action.ItemData;

class ItemTransferTest {
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.registries();
	}

	@Test
	void insertTopsUpStacksThenFillsEmptySlots() {
		SimpleContainer chest = new SimpleContainer(3);
		chest.setItem(1, new ItemStack(Items.DIAMOND, 60));
		chest.setItem(2, new ItemStack(Items.DIRT, 1));

		assertTrue(ItemTransfer.insert(chest, diamonds(66)), "4 top up slot 1, 62 go into the empty slot");

		assertEquals(List.of("62 minecraft:diamond", "64 minecraft:diamond", "1 minecraft:dirt"), contents(chest));
	}

	@Test
	void insertIsAllOrNothing() {
		SimpleContainer chest = new SimpleContainer(2);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 60));
		chest.setItem(1, new ItemStack(Items.DIRT, 1));
		List<String> before = contents(chest);

		assertFalse(ItemTransfer.insert(chest, diamonds(5)), "only 4 fit");
		assertEquals(before, contents(chest));
	}

	@Test
	void removeTakesFromAnySlotAndIsAllOrNothing() {
		SimpleContainer chest = new SimpleContainer(3);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
		chest.setItem(2, new ItemStack(Items.DIAMOND, 5));
		List<String> before = contents(chest);

		assertFalse(ItemTransfer.remove(chest, diamonds(11)));
		assertEquals(before, contents(chest));

		assertTrue(ItemTransfer.remove(chest, diamonds(7)));
		assertEquals(List.of("3 minecraft:diamond", "0 minecraft:air", "0 minecraft:air"), contents(chest));
	}

	@Test
	void itemDataSurvivesEncoding() {
		HolderLookup.Provider registries = TestBootstrap.registries();
		ItemData diamonds = diamonds(250);
		assertEquals(diamonds, ItemData.decode(diamonds.encode(registries), registries).orElseThrow(), "counts above a stack size survive");
	}

	private static ItemData diamonds(int count) {
		return new ItemData(new ItemStackTemplate(Items.DIAMOND), count);
	}

	private static List<String> contents(SimpleContainer container) {
		List<String> contents = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			contents.add(stack.getCount() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem()));
		}
		return contents;
	}
}
