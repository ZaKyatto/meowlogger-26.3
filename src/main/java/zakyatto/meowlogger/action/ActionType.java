package zakyatto.meowlogger.action;

import java.util.Optional;

/**
 * Kinds of logged actions. {@link #id()} is the value persisted in the database, so existing ids
 * must never change or be reused.
 */
public enum ActionType {
	BLOCK_BREAK(1, "block-break"),
	BLOCK_PLACE(2, "block-place");

	private static final ActionType[] VALUES = values();

	private final int id;
	private final String key;

	ActionType(int id, String key) {
		this.id = id;
		this.key = key;
	}

	/** Stable database id of this action type. */
	public int id() {
		return id;
	}

	/** Name used in command parameters, e.g. {@code block-break}. */
	public String key() {
		return key;
	}

	/** @throws IllegalArgumentException if no type has this id */
	public static ActionType fromId(int id) {
		for (ActionType type : VALUES) {
			if (type.id == id) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown action type id " + id);
	}

	public static Optional<ActionType> fromKey(String key) {
		for (ActionType type : VALUES) {
			if (type.key.equals(key)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
