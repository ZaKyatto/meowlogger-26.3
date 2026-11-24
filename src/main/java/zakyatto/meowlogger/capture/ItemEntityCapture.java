package zakyatto.meowlogger.capture;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * Logs players dropping items (including on death) and picking them up. Both are stored as the item
 * entity, so a rollback can put picked-up items back on the ground, or remove dropped items that
 * are still lying there. Server thread only.
 */
public final class ItemEntityCapture {
	private static final String TAG_ITEM = "Item";
	private static final String TAG_AGE = "Age";

	private ItemEntityCapture() {
	}

	/** Called after {@code player} dropped {@code entity}. */
	public static void dropped(ServerPlayer player, ItemEntity entity) {
		if (entity != null && !entity.getItem().isEmpty()) {
			EntityCapture.log(ActionType.ITEM_DROP, new EntityCapture.Cause(ActionRecord.PLAYER_SOURCE, player), entity,
					null, EntityCapture.save(entity), itemId(entity.getItem()));
		}
	}

	/**
	 * Called after a player touched an item entity that held {@code before} (a copy). Logs how many
	 * items they picked up, if any; a full inventory may take only part of the stack. Vanilla calls
	 * this every tick a player stands on an item, so nothing is saved unless something was picked up.
	 */
	public static void afterTouch(Player player, ItemEntity entity, ItemStack before) {
		if (before.isEmpty() || !(player instanceof ServerPlayer)) {
			return;
		}
		int left = entity.isRemoved() ? 0 : entity.getItem().getCount();
		int picked = before.getCount() - left;
		if (picked <= 0) {
			return;
		}
		// What a rollback puts back: the picked-up part, as a new item entity that won't despawn right away.
		CompoundTag data = EntityCapture.save(entity);
		data.store(TAG_ITEM, ItemStack.CODEC, entity.registryAccess().createSerializationContext(NbtOps.INSTANCE),
				before.copyWithCount(picked));
		data.store(Entity.TAG_UUID, UUIDUtil.CODEC, UUID.randomUUID());
		data.remove(TAG_AGE);
		EntityCapture.log(ActionType.ITEM_PICKUP, new EntityCapture.Cause(ActionRecord.PLAYER_SOURCE, player), entity,
				data, null, itemId(before));
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
