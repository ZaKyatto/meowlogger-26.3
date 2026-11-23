package zakyatto.meowlogger.action;

/**
 * Kinds of logged actions. {@link #id()} is the value persisted in the database, so existing ids
 * must never change or be reused.
 */
public enum ActionType {
	BLOCK_BREAK(1),
	BLOCK_PLACE(2);

	private final int id;

	ActionType(int id) {
		this.id = id;
	}

	/** Stable database id of this action type. */
	public int id() {
		return id;
	}
}
