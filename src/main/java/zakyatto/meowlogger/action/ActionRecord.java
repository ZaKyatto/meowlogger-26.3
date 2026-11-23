package zakyatto.meowlogger.action;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One logged action. Every component is immutable, so a record built on the server thread can be
 * handed to the writer thread without copying.
 *
 * @param timeMillis epoch milliseconds when the action happened
 * @param type       what kind of action this was
 * @param playerId   UUID of the acting player
 * @param playerName name of the acting player at the time of the action
 * @param world      dimension the action happened in
 * @param pos        affected block position
 * @param oldState   block state before the action
 * @param newState   block state after the action
 */
public record ActionRecord(
		long timeMillis,
		ActionType type,
		UUID playerId,
		String playerName,
		ResourceKey<Level> world,
		BlockPos pos,
		BlockState oldState,
		BlockState newState
) {
	public ActionRecord {
		pos = pos.immutable();
	}
}
