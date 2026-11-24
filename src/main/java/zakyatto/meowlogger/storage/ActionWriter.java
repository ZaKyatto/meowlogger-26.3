package zakyatto.meowlogger.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.query.ActionFilter;

/**
 * Owns a background thread that drains queued actions and writes them to a {@link Database} in
 * batches. The submit methods are safe to call from the server thread: they never block or touch
 * the database.
 */
public final class ActionWriter implements AutoCloseable {
	private static final int MAX_BATCH_SIZE = 1_000;
	private static final int MAX_WRITE_ATTEMPTS = 3;
	private static final int PURGE_CHUNK_SIZE = 10_000;

	// shortcut: unbounded, so a database that stays down grows memory; add a cap + drop counter if that shows up in practice
	private final BlockingQueue<ActionRecord> queue = new LinkedBlockingQueue<>();
	private final Queue<FlagUpdate> flagUpdates = new ConcurrentLinkedQueue<>();
	private final Queue<Purge> purges = new ConcurrentLinkedQueue<>();
	private final AtomicLong submitted = new AtomicLong();
	private long processed; // writer thread only
	private final Database database;
	private final Thread thread;
	private volatile boolean running = true;

	private record FlagUpdate(long[] ids, boolean rolledBack) {
	}

	private static final class Purge {
		final ActionFilter filter;
		/** The purge starts once this many submitted actions have been processed, so it also covers those. */
		final long startAfter;
		final CompletableFuture<Integer> result = new CompletableFuture<>();
		int deleted;

		Purge(ActionFilter filter, long startAfter) {
			this.filter = filter;
			this.startAfter = startAfter;
		}
	}

	/** Takes ownership of {@code database} and starts the writer thread. */
	public ActionWriter(Database database) {
		this.database = database;
		this.thread = Thread.ofPlatform().name("MeowLogger Writer").daemon().start(this::run);
	}

	/** Queues an action for writing. */
	public void submit(ActionRecord record) {
		submitted.incrementAndGet();
		queue.add(record);
	}

	/** Queues an update of the rolled-back flag of already stored actions. */
	public void submitRolledBack(long[] ids, boolean rolledBack) {
		flagUpdates.add(new FlagUpdate(ids, rolledBack));
	}

	/**
	 * Queues a permanent deletion of the actions matching {@code filter}, after the actions queued so
	 * far are written. Large purges run in chunks between batches, so logging continues meanwhile.
	 * The future completes on the writer thread with the number of deleted actions, or exceptionally
	 * if the database fails or the writer is closed first.
	 */
	public CompletableFuture<Integer> submitPurge(ActionFilter filter) {
		if (!running) {
			return CompletableFuture.failedFuture(new IllegalStateException("The writer is closed"));
		}
		Purge purge = new Purge(filter, submitted.get());
		purges.add(purge);
		return purge.result;
	}

	private void run() {
		List<ActionRecord> batch = new ArrayList<>(MAX_BATCH_SIZE);
		while (running || !queue.isEmpty() || !flagUpdates.isEmpty()) {
			ActionRecord first;
			try {
				first = purges.isEmpty() || !running ? queue.poll(250, TimeUnit.MILLISECONDS) : queue.poll();
			} catch (InterruptedException e) {
				continue; // only close() stops this thread, via the running flag
			}
			if (first != null) {
				batch.add(first);
				queue.drainTo(batch, MAX_BATCH_SIZE - 1);
				withRetry(batch.size() + " actions", () -> database.write(batch));
				processed += batch.size();
				batch.clear();
			}
			FlagUpdate update;
			while ((update = flagUpdates.poll()) != null) {
				FlagUpdate current = update;
				withRetry("rolled-back flags of " + current.ids().length + " actions",
						() -> database.setRolledBack(current.ids(), current.rolledBack()));
			}
			if (running) {
				purgeChunk();
			}
		}
		Purge abandoned;
		while ((abandoned = purges.poll()) != null) {
			MeowLogger.LOGGER.warn("Server stopped during a purge; {} actions were deleted before it stopped", abandoned.deleted);
			abandoned.result.completeExceptionally(new CancellationException("The server stopped"));
		}
	}

	private void purgeChunk() {
		Purge purge = purges.peek();
		if (purge == null || processed < purge.startAfter) {
			return;
		}
		try {
			int deleted = database.delete(purge.filter, PURGE_CHUNK_SIZE);
			purge.deleted += deleted;
			if (deleted < PURGE_CHUNK_SIZE) {
				purges.poll();
				purge.result.complete(purge.deleted);
			}
		} catch (Exception e) {
			purges.poll();
			purge.result.completeExceptionally(e);
		}
	}

	private interface DatabaseTask {
		void run() throws Exception;
	}

	private void withRetry(String description, DatabaseTask task) {
		for (int attempt = 1; ; attempt++) {
			try {
				task.run();
				return;
			} catch (Exception e) {
				if (attempt == MAX_WRITE_ATTEMPTS) {
					MeowLogger.LOGGER.error("Dropping write of {} after {} failed attempts", description, attempt, e);
					return;
				}
				MeowLogger.LOGGER.warn("Failed to write {} (attempt {}/{}), retrying", description, attempt, MAX_WRITE_ATTEMPTS, e);
				try {
					Thread.sleep(1_000L * attempt);
				} catch (InterruptedException ignored) {
					// retry immediately
				}
			}
		}
	}

	/** Writes everything still queued, stops the thread and closes the database. Blocks until done. */
	@Override
	public void close() {
		running = false;
		boolean interrupted = false;
		while (thread.isAlive()) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		try {
			database.close();
		} catch (Exception e) {
			MeowLogger.LOGGER.error("Failed to close database", e);
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
