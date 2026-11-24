package zakyatto.meowlogger.query;

import zakyatto.meowlogger.action.ActionType;

/**
 * A stored action as read back from the database, with ids resolved to names.
 *
 * @param source     what caused the action, e.g. {@code minecraft:player} or {@code minecraft:creeper}
 * @param playerName name of the responsible player, or null if none
 * @param oldObject  block identifier before the action, e.g. {@code minecraft:stone}
 * @param oldState   block state properties before the action, e.g. {@code [axis=y]}, or null if none
 * @param oldData    gzip-compressed NBT of the block entity before the action, or null if none
 * @param newObject  block identifier after the action
 * @param newState   block state properties after the action, or null if none
 * @param newData    gzip-compressed NBT of the block entity after the action, or null if none
 * @param rolledBack whether the action is currently rolled back
 */
public record LoggedAction(
		long id,
		long timeMillis,
		ActionType type,
		String source,
		String playerName,
		String world,
		int x,
		int y,
		int z,
		String oldObject,
		String oldState,
		byte[] oldData,
		String newObject,
		String newState,
		byte[] newData,
		boolean rolledBack
) {
}
