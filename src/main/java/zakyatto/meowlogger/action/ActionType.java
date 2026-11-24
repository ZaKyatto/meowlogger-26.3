package zakyatto.meowlogger.action;

import java.util.Optional;

/**
 * Kinds of logged actions. {@link #id()} is the value persisted in the database, so existing ids
 * must never change or be reused.
 */
public enum ActionType {
	BLOCK_BREAK(1, "block-break", Kind.BLOCK),
	BLOCK_PLACE(2, "block-place", Kind.BLOCK),
	/** A player put items into a container. */
	ITEM_INSERT(3, "item-insert", Kind.ITEM),
	/** A player took items out of a container. */
	ITEM_REMOVE(4, "item-remove", Kind.ITEM),
	/** A living entity (not a player) died. */
	ENTITY_KILL(5, "entity-kill", Kind.ENTITY);

	/** What an action changes, which decides how it is stored and rolled back. */
	public enum Kind {
		/** Replaces one block state with another. */
		BLOCK,
		/** Moves items into or out of a container; the container's block is unchanged. */
		ITEM,
		/** Removes an entity from the world. */
		ENTITY
	}

	private static final ActionType[] VALUES = values();

	private final int id;
	private final String key;
	private final Kind kind;

	ActionType(int id, String key, Kind kind) {
		this.id = id;
		this.key = key;
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
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
