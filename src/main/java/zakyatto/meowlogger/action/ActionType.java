package zakyatto.meowlogger.action;

import java.util.Optional;

/**
 * Kinds of logged actions. {@link #id()} is the value persisted in the database, so existing ids
 * must never change or be reused.
 */
public enum ActionType {
	BLOCK_BREAK(1, "block-break", true),
	BLOCK_PLACE(2, "block-place", true),
	/** A player put items into a container. */
	ITEM_INSERT(3, "item-insert", false),
	/** A player took items out of a container. */
	ITEM_REMOVE(4, "item-remove", false);

	private static final ActionType[] VALUES = values();

	private final int id;
	private final String key;
	private final boolean blockChange;

	ActionType(int id, String key, boolean blockChange) {
		this.id = id;
		this.key = key;
		this.blockChange = blockChange;
	}

	/**
	 * Whether this action replaces one block state with another. Otherwise it changes a container's
	 * contents, and the old and new states are both the container's.
	 */
	public boolean isBlockChange() {
		return blockChange;
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
