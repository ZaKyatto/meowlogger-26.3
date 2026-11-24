package zakyatto.meowlogger.action;

import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * The items moved by an item action: what (item plus components, such as enchantments or a custom
 * name) and how many. The count is stored apart from the item because it can exceed a stack size.
 *
 * @param item  the item, with a count of 1
 * @param count how many were moved, at least 1
 */
public record ItemData(ItemStackTemplate item, int count) {
	private static final String ITEM_KEY = "Item";
	private static final String COUNT_KEY = "Count";

	public ItemData {
		if (count < 1) {
			throw new IllegalArgumentException("count must be positive");
		}
		item = item.withCount(1);
	}

	/** Encodes this as NBT. Needs the server's registries, since item components can refer to them. */
	public CompoundTag encode(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.put(ITEM_KEY, ItemStackTemplate.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), item).getOrThrow());
		tag.putInt(COUNT_KEY, count);
		return tag;
	}

	/**
	 * Identifies an entity container (a chest minecart, chest boat) for an item action: its type and
	 * UUID, stored as the action's old data in place of a container block.
	 */
	public static CompoundTag entityContainer(Entity entity) {
		CompoundTag tag = new CompoundTag();
		tag.putString(Entity.TAG_ID, EntityType.getKey(entity.getType()).toString());
		tag.store(Entity.TAG_UUID, UUIDUtil.CODEC, entity.getUUID());
		return tag;
	}

	/** Decodes {@link #encode} output, or returns empty if it is malformed or names items that no longer exist. */
	public static Optional<ItemData> decode(CompoundTag tag, HolderLookup.Provider registries) {
		Tag itemTag = tag.get(ITEM_KEY);
		int count = tag.getIntOr(COUNT_KEY, 0);
		if (itemTag == null || count < 1) {
			return Optional.empty();
		}
		return ItemStackTemplate.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), itemTag).result()
				.map(item -> new ItemData(item, count));
	}

	/** Creates a stack of this item with the given count. */
	public ItemStack stack(int stackCount) {
		return item.withCount(stackCount).create();
	}
}
