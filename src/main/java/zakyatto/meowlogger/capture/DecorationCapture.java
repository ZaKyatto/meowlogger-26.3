package zakyatto.meowlogger.capture;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * Logs armor stands, item frames, paintings, boats and minecarts being placed, broken, and changed
 * (an item frame's item or rotation, an armor stand's equipment). Called from the mixins in
 * {@code zakyatto.meowlogger.mixin}. Server thread only.
 */
public final class DecorationCapture {
	/** An item frame or painting dropping because the block it hung on is gone. */
	public static final String SUPPORT_SOURCE = "meowlogger:support_broken";

	private static ServerPlayer actingPlayer;
	private static int hurtDepth;

	private DecorationCapture() {
	}

	/** Whether {@code entity} is a kind this class logs. */
	public static boolean isDecoration(Entity entity) {
		return entity instanceof ArmorStand || entity instanceof VehicleEntity
				|| entity instanceof BlockAttachedEntity && !(entity instanceof LeashFenceKnotEntity);
	}

	/**
	 * Marks {@code player} as using an item until {@link #endItemUse}, so decorations spawned
	 * meanwhile (placed armor stands, item frames, boats) are logged as placed by them.
	 *
	 * @return the previously acting player, to pass to {@link #endItemUse}
	 */
	public static ServerPlayer beginItemUse(ServerPlayer player) {
		ServerPlayer previous = actingPlayer;
		actingPlayer = player;
		return previous;
	}

	public static void endItemUse(ServerPlayer previous) {
		actingPlayer = previous;
	}

	/** Called after an entity was added to a world. */
	public static void entityAdded(Entity entity) {
		if (actingPlayer != null && isDecoration(entity)) {
			EntityCapture.log(ActionType.ENTITY_PLACE, new EntityCapture.Cause(ActionRecord.PLAYER_SOURCE, actingPlayer),
					entity, null, EntityCapture.save(entity));
		}
	}

	/**
	 * Called before an entity is damaged. Returns its saved data, or null if it is not logged or the
	 * call is nested (an override calling the method it overrides).
	 */
	public static CompoundTag beforeHurt(Entity entity) {
		return hurtDepth++ == 0 && isDecoration(entity) ? EntityCapture.save(entity) : null;
	}

	/**
	 * Called after an entity was damaged, with what {@link #beforeHurt} returned. Logs it as broken if
	 * it is gone, or as changed if an item frame lost its item.
	 */
	public static void afterHurt(Entity entity, DamageSource source, CompoundTag before) {
		hurtDepth--;
		if (before == null) {
			return;
		}
		if (entity.isRemoved()) {
			EntityCapture.log(ActionType.ENTITY_BREAK, EntityCapture.cause(source), entity, before, null);
		} else if (entity instanceof ItemFrame) {
			logIfChanged(EntityCapture.cause(source), entity, before);
		}
	}

	/** Called before a player interacts with an item frame or armor stand. */
	public static CompoundTag beforeInteract(Entity entity) {
		return EntityCapture.save(entity);
	}

	/** Called after a player interacted with an item frame or armor stand. */
	public static void afterInteract(Player player, Entity entity, CompoundTag before) {
		if (player instanceof ServerPlayer && !entity.isRemoved()) {
			logIfChanged(new EntityCapture.Cause(ActionRecord.PLAYER_SOURCE, player), entity, before);
		}
	}

	/** Called just before an item frame or painting is removed because its supporting block is gone. */
	public static void supportLost(Entity entity) {
		if (isDecoration(entity)) {
			EntityCapture.log(ActionType.ENTITY_BREAK, new EntityCapture.Cause(SUPPORT_SOURCE, null), entity,
					EntityCapture.save(entity), null);
		}
	}

	private static void logIfChanged(EntityCapture.Cause cause, Entity entity, CompoundTag before) {
		CompoundTag after = EntityCapture.save(entity);
		if (!after.equals(before)) {
			EntityCapture.log(ActionType.ENTITY_CHANGE, cause, entity, before, after);
		}
	}
}
