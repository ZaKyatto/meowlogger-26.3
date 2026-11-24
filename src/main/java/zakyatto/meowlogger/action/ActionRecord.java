package zakyatto.meowlogger.action;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One logged action. The record is handed from the server thread to the writer thread without
 * copying, so every component must be immutable or exclusively owned by the record (the NBT tags
 * are fresh snapshots that nothing else references).
 *
 * @param timeMillis epoch milliseconds when the action happened
 * @param type       what kind of action this was
 * @param source     what caused it, e.g. {@code minecraft:player}, {@code minecraft:creeper}, {@code minecraft:fire}
 * @param playerId   UUID of the responsible player (the actor, or e.g. whoever lit the TNT), or null if none
 * @param playerName name of the responsible player at the time of the action, or null if none
 * @param world      dimension the action happened in
 * @param pos        affected block position; for entity actions, the block the entity was in
 * @param oldState   block state before the action; for item actions, the container's state; null for entity actions
 * @param oldData    block entity data before the action (e.g. chest contents), or null if none; for
 *                   entity actions, the entity's saved data
 * @param newState   block state after the action; for item actions, the container's state; null for entity actions
 * @param newData    block entity data after the action, or null if none; for item actions, the
 *                   moved items as written by {@link ItemData#encode}
 * @param object     for item actions, the moved item's identifier, e.g. {@code minecraft:diamond}; for
 *                   entity actions, the entity type, e.g. {@code minecraft:cow}; null for block changes
 */
public record ActionRecord(
		long timeMillis,
		ActionType type,
		String source,
		UUID playerId,
		String playerName,
		ResourceKey<Level> world,
		BlockPos pos,
		BlockState oldState,
		CompoundTag oldData,
		BlockState newState,
		CompoundTag newData,
		String object
) {
	/** Source of actions a player performed directly. */
	public static final String PLAYER_SOURCE = "minecraft:player";

	public ActionRecord {
		if ((playerId == null) != (playerName == null)) {
			throw new IllegalArgumentException("playerId and playerName must both be set or both be null");
		}
		boolean entity = type.kind() == ActionType.Kind.ENTITY;
		if ((type.kind() == ActionType.Kind.BLOCK) == (object != null)) {
			throw new IllegalArgumentException("object must be set exactly for item and entity actions");
		}
		if (entity != (oldState == null) || entity != (newState == null)) {
			throw new IllegalArgumentException("block states must be set exactly for block and item actions");
		}
		pos = pos.immutable();
	}
}
