package zakyatto.meowlogger.storage;

import net.minecraft.world.level.block.state.BlockState;

/** Exposes package-private storage helpers to tests in other packages. */
public final class SqliteDatabaseAccess {
	private SqliteDatabaseAccess() {
	}

	/** The property string exactly as {@link SqliteDatabase} stores it. */
	public static String properties(BlockState state) {
		return SqliteDatabase.properties(state);
	}
}
