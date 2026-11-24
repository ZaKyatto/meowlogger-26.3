package zakyatto.meowlogger.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.query.ActionFilter;

class FilterParserTest {
	private static final BlockPos POS = new BlockPos(10, 64, 10);
	private static final long NOW = 100_000_000L;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void parsesAllParameters() throws CommandSyntaxException {
		ActionFilter filter = parse("radius:5 player:Steve,alex source:tnt,Minecraft:Creeper action:block-break,BLOCK-PLACE time:1h30m block:stone,minecraft:oak_stairs world:the_nether");
		assertEquals(Level.NETHER, filter.world(), "world overrides the source world");
		assertEquals(new BlockPos(5, 59, 5), filter.min());
		assertEquals(new BlockPos(15, 69, 15), filter.max());
		assertEquals(Set.of("Steve", "alex"), filter.players());
		assertEquals(Set.of("minecraft:tnt", "minecraft:creeper"), filter.sources());
		assertEquals(Set.of(ActionType.BLOCK_BREAK, ActionType.BLOCK_PLACE), filter.types());
		assertEquals(Set.of("minecraft:stone", "minecraft:oak_stairs"), filter.objects());
		assertEquals(NOW - 5_400_000L, filter.sinceMillis());
	}

	@Test
	void acceptsShortKeysAndMergesLists() throws CommandSyntaxException {
		ActionFilter filter = parse("  r:0  p:a, p:b a:block-place b:dirt t:10s ");
		assertEquals(Level.OVERWORLD, filter.world(), "radius defaults to the source world");
		assertEquals(POS, filter.min());
		assertEquals(POS, filter.max());
		assertEquals(Set.of("a", "b"), filter.players());
		assertEquals(NOW - 10_000L, filter.sinceMillis());
	}

	@Test
	void withoutRadiusSearchesEverywhere() throws CommandSyntaxException {
		ActionFilter filter = parse("player:Steve");
		assertNull(filter.world());
		assertNull(filter.min());
		assertEquals(0, filter.sinceMillis());
	}

	@Test
	void parsesDurations() throws CommandSyntaxException {
		assertEquals(90_061_000L, FilterParser.parseDuration("1d1h1m1s"));
		assertEquals(1_209_600_000L, FilterParser.parseDuration("2W"));
		assertThrows(CommandSyntaxException.class, () -> FilterParser.parseDuration("999999999w".repeat(16)), "overflow");
	}

	@Test
	void rejectsInvalidInput() {
		for (String input : new String[] {
				"foo:bar", "radius", "radius:", ":5", "radius:-1", "radius:abc", "radius:1001",
				"time:5", "time:1x", "time:h", "time:1h-", "action:explode", "block:not_a_block", "block:Bad Block", "world:!!", "source:!!"
		}) {
			assertThrows(CommandSyntaxException.class, () -> parse(input), input);
		}
	}

	private static ActionFilter parse(String input) throws CommandSyntaxException {
		return FilterParser.parse(input, Level.OVERWORLD, POS, NOW);
	}
}
