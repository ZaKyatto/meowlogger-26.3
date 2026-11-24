package zakyatto.meowlogger.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zakyatto.meowlogger.TestBootstrap;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.query.ActionFilter;

class ActionWriterTest {
	private static final UUID MEOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final ActionFilter ALL = new ActionFilter(null, null, null, Set.of(), Set.of(), Set.of(), Set.of(), 0, 0, null, Set.of());

	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.registries();
	}

	@Test
	void purgesAfterEarlierQueuedWritesAcrossManyChunks() throws Exception {
		Path file = dir.resolve("writer.sqlite");
		ActionWriter writer = new ActionWriter(new SqliteDatabase(file));
		int total = 25_000;
		for (int i = 0; i < total; i++) {
			writer.submit(record(i, new BlockPos(i % 100, 64, i / 100)));
		}
		CompletableFuture<Integer> purge = writer.submitPurge(
				new ActionFilter(null, null, null, Set.of(), Set.of(), Set.of(), Set.of(), 0, 20_000, null, Set.of()));
		assertEquals(20_000, purge.get(30, TimeUnit.SECONDS), "every action queued before the purge is written first");
		writer.close();

		try (SqliteDatabase db = new SqliteDatabase(file)) {
			assertEquals(total - 20_000, db.count(ALL));
		}
	}

	@Test
	void closingCancelsUnfinishedPurges() throws SQLException {
		ActionWriter writer = new ActionWriter(new SqliteDatabase(dir.resolve("closed.sqlite")));
		writer.close();
		CompletableFuture<Integer> purge = writer.submitPurge(ALL);
		assertThrows(ExecutionException.class, () -> purge.get(5, TimeUnit.SECONDS));
	}

	private static ActionRecord record(long time, BlockPos pos) {
		return new ActionRecord(time, ActionType.BLOCK_BREAK, ActionRecord.PLAYER_SOURCE, MEOW, "Meow", Level.OVERWORLD, pos,
				Blocks.STONE.defaultBlockState(), null, Blocks.AIR.defaultBlockState(), null, null);
	}
}
