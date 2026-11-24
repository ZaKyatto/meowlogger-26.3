package zakyatto.meowlogger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import zakyatto.meowlogger.TestBootstrap;
import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

class MeowConfigTest {
	private static final UUID MEOW = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.registries();
	}

	@Test
	void createsTheFileWithDefaultsAndReadsItBack() throws IOException {
		Path file = dir.resolve("config/meowlogger.json");
		assertEquals(MeowConfig.DEFAULTS, MeowConfig.load(file).config());
		assertTrue(Files.readString(file).contains("// Automatically delete actions older than"), "the file explains its options");
		MeowConfig.Loaded reloaded = MeowConfig.load(file);
		assertEquals(MeowConfig.DEFAULTS, reloaded.config());
		assertEquals(0, reloaded.warnings().size());
	}

	@Test
	void normalizesValuesAndFallsBackOnInvalidOnes() {
		MeowConfig.Loaded loaded = MeowConfig.parse(JsonParser.parseString("""
				{
				  "ignoredActions": ["BLOCK-PLACE", "explode"],
				  "ignoredObjects": ["short_grass", "minecraft:cow", 5],
				  "ignoredSources": "fire",
				  "retentionDays": 30,
				  "maxRadius": -1,
				  "lookupPageSize": 2.5,
				  "logHostileMobKills": "yes",
				  "somethingUnknown": true
				}""").getAsJsonObject());
		MeowConfig config = loaded.config();
		assertEquals(Set.of(ActionType.BLOCK_PLACE), config.ignoredActions());
		assertEquals(Set.of("minecraft:short_grass", "minecraft:cow"), config.ignoredObjects());
		assertEquals(Set.of("minecraft:fire"), config.ignoredSources());
		assertEquals(30, config.retentionDays());
		assertEquals(MeowConfig.DEFAULTS.maxRadius(), config.maxRadius());
		assertEquals(MeowConfig.DEFAULTS.lookupPageSize(), config.lookupPageSize());
		assertFalse(config.logHostileMobKills());
		assertEquals(5, loaded.warnings().size(), String.join("\n", loaded.warnings()));
	}

	@Test
	void rejectsFilesThatAreNotJsonObjects() throws IOException {
		Path file = dir.resolve("broken.json");
		Files.writeString(file, "{ \"retentionDays\": ");
		assertThrows(IOException.class, () -> MeowConfig.load(file));
		Files.writeString(file, "[]");
		assertThrows(IOException.class, () -> MeowConfig.load(file));
	}

	@Test
	void ignoresActionsByTypeWorldSourceAndObject() {
		MeowConfig config = MeowConfig.parse(JsonParser.parseString("""
				{"ignoredActions": ["item-remove"], "ignoredWorlds": ["the_end"], "ignoredSources": ["fire"],
				 "ignoredObjects": ["short_grass", "furnace", "cow"]}""").getAsJsonObject()).config();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
		BlockState stone = Blocks.STONE.defaultBlockState();

		assertFalse(config.shouldLog(block(ActionType.BLOCK_BREAK, Level.OVERWORLD, ActionRecord.PLAYER_SOURCE, grass, air)));
		assertTrue(config.shouldLog(block(ActionType.BLOCK_PLACE, Level.OVERWORLD, ActionRecord.PLAYER_SOURCE, grass, stone)),
				"placing stone where grass was is still logged");
		assertFalse(config.shouldLog(block(ActionType.BLOCK_BREAK, Level.END, ActionRecord.PLAYER_SOURCE, stone, air)));
		assertFalse(config.shouldLog(block(ActionType.BLOCK_BREAK, Level.OVERWORLD, "minecraft:fire", stone, air)));
		assertTrue(config.shouldLog(block(ActionType.BLOCK_BREAK, Level.OVERWORLD, "minecraft:tnt", stone, air)));
		assertFalse(config.shouldLog(item(ActionType.ITEM_INSERT, Blocks.FURNACE.defaultBlockState(), "minecraft:coal")));
		assertTrue(config.shouldLog(item(ActionType.ITEM_INSERT, Blocks.CHEST.defaultBlockState(), "minecraft:coal")));
		assertFalse(config.shouldLog(item(ActionType.ITEM_REMOVE, Blocks.CHEST.defaultBlockState(), "minecraft:coal")));
		assertFalse(config.shouldLog(new ActionRecord(0, ActionType.ENTITY_KILL, ActionRecord.PLAYER_SOURCE, MEOW, "Meow", Level.OVERWORLD,
				BlockPos.ZERO, null, new CompoundTag(), null, null, "minecraft:cow")));
		CompoundTag cart = new CompoundTag();
		cart.putString("id", "minecraft:furnace_minecart");
		assertTrue(config.shouldLog(new ActionRecord(0, ActionType.ITEM_INSERT, ActionRecord.PLAYER_SOURCE, MEOW, "Meow", Level.OVERWORLD,
				BlockPos.ZERO, null, cart, null, new CompoundTag(), "minecraft:coal")), "an entity container is matched by its type");
		assertTrue(MeowConfig.DEFAULTS.shouldLog(block(ActionType.BLOCK_BREAK, Level.END, "minecraft:fire", grass, air)));
	}

	private static ActionRecord block(ActionType type, ResourceKey<Level> world, String source,
			BlockState oldState, BlockState newState) {
		return new ActionRecord(0, type, source, null, null, world, BlockPos.ZERO, oldState, null, newState, null, null);
	}

	private static ActionRecord item(ActionType type, BlockState container, String item) {
		return new ActionRecord(0, type, ActionRecord.PLAYER_SOURCE, MEOW, "Meow", Level.OVERWORLD, BlockPos.ZERO,
				container, null, container, new CompoundTag(), item);
	}
}
