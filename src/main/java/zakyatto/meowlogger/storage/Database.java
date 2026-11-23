package zakyatto.meowlogger.storage;

import java.sql.SQLException;
import java.util.List;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.query.LoggedAction;

/**
 * A storage backend for logged actions. Implementations are not thread-safe per method group:
 * {@link #write} is called from one writer thread and the lookup methods from one reader thread,
 * and the two groups may run concurrently.
 */
public interface Database extends AutoCloseable {
	/**
	 * Persists all records in a single transaction. If this throws, none of the records were stored
	 * and the call may be retried with the same list.
	 */
	void write(List<ActionRecord> records) throws SQLException;

	/** Counts the stored actions matching {@code filter}. */
	int count(ActionFilter filter) throws SQLException;

	/** Returns matching actions, newest first, skipping {@code offset} and returning at most {@code limit}. */
	List<LoggedAction> lookup(ActionFilter filter, int offset, int limit) throws SQLException;

	@Override
	void close() throws SQLException;
}
