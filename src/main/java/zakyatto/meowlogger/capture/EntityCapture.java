package zakyatto.meowlogger.capture;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueOutput;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * Logs living entities dying, with their data so a rollback can bring them back. Players are not
 * logged, and by default neither are hostile mobs unless they are persistent (e.g. name-tagged),
 * since killing those is normal play. Server thread only.
 */
public final class EntityCapture {
	/** Data saved when an entity received a lethal hit, before its death loot drops. */
	private record Pending(LivingEntity entity, CompoundTag data) {
	}

	private static Pending pending;

	private EntityCapture() {
	}

	/** Registers the death listeners. Call once from the mod initializer. */
	public static void register() {
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (isLogged(entity)) {
				pending = new Pending(entity, save(entity));
			}
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register(EntityCapture::afterDeath);
	}

	private static void afterDeath(LivingEntity entity, DamageSource source) {
		Pending saved = pending;
		pending = null;
		if (!isLogged(entity)) {
			return;
		}
		// Deaths that skip the damage path (no lethal hit) have no pending data; their loot has dropped by now.
		CompoundTag data = saved != null && saved.entity() == entity ? saved.data() : save(entity);
		log(ActionType.ENTITY_KILL, cause(source), entity, data, null);
	}

	/**
	 * What caused damage, as a logged source plus the responsible player if any. A player hitting
	 * directly is {@code minecraft:player}; otherwise the source is the projectile or other direct
	 * entity (e.g. {@code arrow}, {@code tnt}), the attacker, or the damage type (e.g. {@code lava}).
	 */
	record Cause(String source, Player player) {
	}

	static Cause cause(DamageSource source) {
		Entity attacker = source.getEntity();
		Entity direct = source.getDirectEntity();
		Player player = attacker instanceof ServerPlayer serverPlayer ? serverPlayer : null;
		if (player != null && direct == player) {
			return new Cause(ActionRecord.PLAYER_SOURCE, player);
		}
		if (direct != null) {
			return new Cause(EntityType.getKey(direct.getType()).toString(), player);
		}
		if (attacker != null) {
			return new Cause(EntityType.getKey(attacker.getType()).toString(), player);
		}
		return new Cause(source.typeHolder().getRegisteredName(), null);
	}

	/** Logs an entity action; {@code oldData} or {@code newData} is null when the entity did not exist before or after. */
	static void log(ActionType type, Cause cause, Entity entity, CompoundTag oldData, CompoundTag newData) {
		log(type, cause, entity, oldData, newData, EntityType.getKey(entity.getType()).toString());
	}

	/** Like {@link #log(ActionType, Cause, Entity, CompoundTag, CompoundTag)}, stored under {@code object} (e.g. an item id). */
	static void log(ActionType type, Cause cause, Entity entity, CompoundTag oldData, CompoundTag newData, String object) {
		Player player = cause.player();
		MeowLogger.log(new ActionRecord(System.currentTimeMillis(), type, cause.source(),
				player == null ? null : player.getUUID(), player == null ? null : player.getGameProfile().name(),
				entity.level().dimension(), entity.blockPosition(), null, oldData, null, newData, object));
	}

	private static boolean isLogged(LivingEntity entity) {
		if (entity instanceof Player || !entity.getType().canSerialize()) {
			return false;
		}
		return !(entity instanceof Enemy) || MeowLogger.config().logHostileMobKills()
				|| entity instanceof Mob mob && mob.isPersistenceRequired();
	}

	/**
	 * Saves the entity with its type id. Riders are left out: they are still alive, so bringing them
	 * back with the entity would duplicate them.
	 */
	static CompoundTag save(Entity entity) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
		output.putString(Entity.TAG_ID, EntityType.getKey(entity.getType()).toString());
		entity.saveWithoutId(output);
		CompoundTag data = output.buildResult();
		data.remove(Entity.TAG_PASSENGERS);
		return data;
	}
}
