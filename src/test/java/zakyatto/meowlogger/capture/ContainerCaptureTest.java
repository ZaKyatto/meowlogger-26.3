package zakyatto.meowlogger.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import zakyatto.meowlogger.TestBootstrap;

class ContainerCaptureTest {
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.registries();
	}

	@Test
	void reportsNetChangesPerDistinctItem() {
		ItemStack named = new ItemStack(Items.DIAMOND, 2);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Shiny"));
		List<ItemStack> before = List.of(
				new ItemStack(Items.DIAMOND, 10), new ItemStack(Items.DIRT, 64), ItemStack.EMPTY, new ItemStack(Items.STONE, 5));
		List<ItemStack> after = List.of(
				new ItemStack(Items.DIAMOND, 3), ItemStack.EMPTY, named, new ItemStack(Items.STONE, 5));

		Map<ItemStackTemplate, Integer> changes = ContainerCapture.diff(before, after);

		assertEquals(Map.of(
				new ItemStackTemplate(Items.DIAMOND), -7,
				new ItemStackTemplate(Items.DIRT), -64,
				ItemStackTemplate.fromNonEmptyStack(named, 1), 2), changes, "a renamed diamond is a different item");
	}

	@Test
	void movingItemsWithinTheContainerIsNotAChange() {
		List<ItemStack> before = List.of(new ItemStack(Items.DIAMOND, 10), ItemStack.EMPTY);
		List<ItemStack> after = List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 6));
		assertEquals(Map.of(), ContainerCapture.diff(before, after));
		assertEquals(Map.of(), ContainerCapture.diff(before, before));
	}
}
