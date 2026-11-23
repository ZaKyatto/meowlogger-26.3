package zakyatto.meowlogger.query;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

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

	/**
	 * Looks up all actions matching {@code filter}, newest first, together with the ids of those that
	 * {@linkplain Database#conflicting conflict} with later actions, then converts both with
	 * {@code mapper} on the lookup thread. Fails with {@link TooManyActionsException} if more than
	 * {@code limit} actions match.
	 */
	public <T> CompletableFuture<T> lookupForRollback(ActionFilter filter, int limit, BiFunction<List<LoggedAction>, LongSet, T> mapper) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				List<LoggedAction> actions = database.lookup(filter, 0, limit + 1);
				if (actions.size() > limit) {
					throw new CompletionException(new TooManyActionsException(limit));
				}
				return mapper.apply(actions, new LongOpenHashSet(database.conflicting(filter)));
			} catch (SQLException e) {
				throw new CompletionException(e);
			}
		}, executor);
	}

	/** Thrown when a lookup matches more actions than the caller allows. */
	public static final class TooManyActionsException extends Exception {
		private final int limit;

		public TooManyActionsException(int limit) {
			super("More than " + limit + " actions match", null, false, false);
			this.limit = limit;
		}

		public int limit() {
			return limit;
		}
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
