package zakyatto.meowlogger.storage;

import java.sql.SQLException;
import java.util.List;

import zakyatto.meowlogger.action.ActionRecord;

/**
 * A storage backend for logged actions. Implementations are not thread-safe: the
 * {@link ActionWriter} thread is the only caller.
 */
public interface Database extends AutoCloseable {
	/**
	 * Persists all records in a single transaction. If this throws, none of the records were stored
	 * and the call may be retried with the same list.
	 */
	void write(List<ActionRecord> records) throws SQLException;

	@Override
	void close() throws SQLException;
}
