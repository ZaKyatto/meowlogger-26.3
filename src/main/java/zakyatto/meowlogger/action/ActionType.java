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
	ENTITY_KILL(5, "entity-kill", Kind.ENTITY),
	/** An armor stand, item frame, painting, boat or minecart was destroyed. */
	ENTITY_BREAK(6, "entity-break", Kind.ENTITY),
	/** A player placed an armor stand, item frame, painting, boat or minecart. */
	ENTITY_PLACE(7, "entity-place", Kind.ENTITY),
	/** A player changed an item frame's item or rotation, or an armor stand's equipment. */
	ENTITY_CHANGE(8, "entity-change", Kind.ENTITY),
	/**
	 * A player changed a block by using it, without placing or breaking it: a book put on a lectern,
	 * a disc into a jukebox, a plant into a flower pot, a log stripped, dirt tilled.
	 */
	BLOCK_CHANGE(9, "block-change", Kind.BLOCK),
	/** A player dropped items (also when dying). The entity is the dropped item entity. */
	ITEM_DROP(10, "item-drop", Kind.ENTITY),
	/** A player picked items up. The entity is the picked-up part of the item entity. */
	ITEM_PICKUP(11, "item-pickup", Kind.ENTITY);

	/** Whether this action's entity is an item entity, stored under its item's identifier instead of {@code minecraft:item}. */
	public boolean isItemEntity() {
		return this == ITEM_DROP || this == ITEM_PICKUP;
	}

	/** What an action changes, which decides how it is stored and rolled back. */
	public enum Kind {
		/** Replaces one block state with another. */
		BLOCK,
		/** Moves items into or out of a container; the container's block is unchanged. */
		ITEM,
		/**
		 * Adds, removes or changes an entity. The old data is the entity before (null if it did not
		 * exist), the new data the entity after (null if it no longer exists).
		 */
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
