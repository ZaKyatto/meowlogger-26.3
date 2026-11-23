package zakyatto.meowlogger.query;

import zakyatto.meowlogger.action.ActionType;

/**
 * A stored action as read back from the database, with ids resolved to names.
 *
 * @param oldObject block identifier before the action, e.g. {@code minecraft:stone}
 * @param oldState  block state properties before the action, e.g. {@code [axis=y]}, or null if none
 * @param newObject block identifier after the action
 * @param newState   block state properties after the action, or null if none
 * @param rolledBack whether the action is currently rolled back
 */
public record LoggedAction(
		long id,
		long timeMillis,
		ActionType type,
		String playerName,
		String world,
		int x,
		int y,
		int z,
		String oldObject,
		String oldState,
		String newObject,
		String newState,
		boolean rolledBack
) {
}
