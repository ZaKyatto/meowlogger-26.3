package zakyatto.meowlogger.query;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.storage.Database;

/**
 * Runs lookups on a dedicated background thread so the server thread never waits on the database.
 * Does not own the {@link Database}; close this service before closing the database.
 */
public final class LookupService implements AutoCloseable {
	/** Number of actions shown per page. */
	public static final int PAGE_SIZE = 8;

	private final Database database;
	private final ExecutorService executor =
			Executors.newSingleThreadExecutor(Thread.ofPlatform().name("MeowLogger Lookup").daemon().factory());

	public LookupService(Database database) {
		this.database = database;
	}

	/**
	 * One page of lookup results.
	 *
	 * @param page      1-based page number that was requested
	 * @param pageCount total number of pages, at least 1
	 * @param total     total number of matching actions
	 * @param actions   the actions on this page, newest first; empty if {@code page > pageCount}
	 */
	public record Page(ActionFilter filter, int page, int pageCount, int total, List<LoggedAction> actions) {
	}

	/**
	 * Looks up one page of actions matching {@code filter}. The future completes on the lookup
	 * thread, exceptionally with a {@link CompletionException} wrapping the {@link SQLException} on
	 * database errors.
	 */
	public CompletableFuture<Page> lookup(ActionFilter filter, int page) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				int total = database.count(filter);
				int pageCount = Math.max(1, Math.ceilDiv(total, PAGE_SIZE));
				List<LoggedAction> actions = page > pageCount
						? List.of()
						: database.lookup(filter, (page - 1) * PAGE_SIZE, PAGE_SIZE);
				return new Page(filter, page, pageCount, total, actions);
			} catch (SQLException e) {
				throw new CompletionException(e);
			}
		}, executor);
	}

	/** Waits for running lookups to finish and stops the lookup thread. Queued lookups are discarded. */
	@Override
	public void close() {
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
				MeowLogger.LOGGER.warn("A lookup is still running after 30 seconds; closing the database anyway");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
