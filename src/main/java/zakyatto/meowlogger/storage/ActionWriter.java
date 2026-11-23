package zakyatto.meowlogger.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.action.ActionRecord;

/**
 * Owns a background thread that drains queued actions and writes them to a {@link Database} in
 * batches. The submit methods are safe to call from the server thread: they never block or touch
 * the database.
 */
public final class ActionWriter implements AutoCloseable {
	private static final int MAX_BATCH_SIZE = 1_000;
	private static final int MAX_WRITE_ATTEMPTS = 3;

	// shortcut: unbounded, so a database that stays down grows memory; add a cap + drop counter if that shows up in practice
	private final BlockingQueue<ActionRecord> queue = new LinkedBlockingQueue<>();
	private final Queue<FlagUpdate> flagUpdates = new ConcurrentLinkedQueue<>();
	private final Database database;
	private final Thread thread;
	private volatile boolean running = true;

	private record FlagUpdate(long[] ids, boolean rolledBack) {
	}

	/** Takes ownership of {@code database} and starts the writer thread. */
	public ActionWriter(Database database) {
		this.database = database;
		this.thread = Thread.ofPlatform().name("MeowLogger Writer").daemon().start(this::run);
	}

	/** Queues an action for writing. */
	public void submit(ActionRecord record) {
		queue.add(record);
	}

	/** Queues an update of the rolled-back flag of already stored actions. */
	public void submitRolledBack(long[] ids, boolean rolledBack) {
		flagUpdates.add(new FlagUpdate(ids, rolledBack));
	}

	private void run() {
		List<ActionRecord> batch = new ArrayList<>(MAX_BATCH_SIZE);
		while (running || !queue.isEmpty() || !flagUpdates.isEmpty()) {
			ActionRecord first;
			try {
				first = queue.poll(250, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				continue; // only close() stops this thread, via the running flag
			}
			if (first != null) {
				batch.add(first);
				queue.drainTo(batch, MAX_BATCH_SIZE - 1);
				withRetry(batch.size() + " actions", () -> database.write(batch));
				batch.clear();
			}
			FlagUpdate update;
			while ((update = flagUpdates.poll()) != null) {
				FlagUpdate current = update;
				withRetry("rolled-back flags of " + current.ids().length + " actions",
						() -> database.setRolledBack(current.ids(), current.rolledBack()));
			}
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
