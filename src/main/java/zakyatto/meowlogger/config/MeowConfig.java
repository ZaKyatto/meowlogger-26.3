package zakyatto.meowlogger.config;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * MeowLogger's settings, read from {@code config/meowlogger.json}. Immutable; a reload replaces the
 * whole object, so readers on any thread always see a consistent set of values.
 *
 * @param ignoredActions           action types that are not logged
 * @param ignoredWorlds            dimensions in which nothing is logged, e.g. {@code minecraft:the_end}
 * @param ignoredSources           sources that are not logged, e.g. {@code minecraft:fire}
 * @param ignoredObjects           blocks, items and entity types that are not logged, e.g. {@code minecraft:short_grass}
 * @param logHostileMobKills       whether kills of ordinary (not name-tagged) hostile mobs are logged
 * @param retentionDays            actions older than this many days are deleted automatically; 0 keeps them forever
 * @param maxRollbackActions       largest number of actions a single rollback or restore may touch
 * @param rollbackTickBudgetMillis time per tick a running rollback may spend changing the world
 * @param lookupPageSize           number of actions per lookup page
 * @param maxRadius                largest {@code radius:} lookup parameter
 */
public record MeowConfig(
		Set<ActionType> ignoredActions,
		Set<String> ignoredWorlds,
		Set<String> ignoredSources,
		Set<String> ignoredObjects,
		boolean logHostileMobKills,
		int retentionDays,
		int maxRollbackActions,
		int rollbackTickBudgetMillis,
		int lookupPageSize,
		int maxRadius
) {
	public static final MeowConfig DEFAULTS = new MeowConfig(Set.of(), Set.of(), Set.of(), Set.of(), false, 0, 250_000, 10, 8, 1_000);

	/** The commented default config, copied to the config folder on first start. */
	private static final String DEFAULT_FILE = "/meowlogger-default-config.json";

	public MeowConfig {
		ignoredActions = ignoredActions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(ignoredActions));
		ignoredWorlds = Set.copyOf(ignoredWorlds);
		ignoredSources = Set.copyOf(ignoredSources);
		ignoredObjects = Set.copyOf(ignoredObjects);
	}

	/** A loaded config plus the problems found in the file, such as unknown action names or out-of-range numbers. */
	public record Loaded(MeowConfig config, List<String> warnings) {
	}

	/**
	 * Reads the config file, creating it from the commented defaults if it does not exist. Invalid values fall
	 * back to their defaults and are reported in {@link Loaded#warnings()}; unknown keys are ignored.
	 *
	 * @throws IOException if the file cannot be read or created, or is not a JSON object
	 */
	public static Loaded load(Path file) throws IOException {
		if (Files.notExists(file)) {
			Files.createDirectories(file.getParent());
			try (InputStream template = MeowConfig.class.getResourceAsStream(DEFAULT_FILE)) {
				if (template == null) {
					throw new IOException("Missing " + DEFAULT_FILE + " in the MeowLogger jar");
				}
				Files.copy(template, file);
			}
			return new Loaded(DEFAULTS, List.of());
		}
		JsonObject json;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader); // lenient, so // comments are allowed
			if (!root.isJsonObject()) {
				throw new IOException(file + " must contain a JSON object");
			}
			json = root.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new IOException("Invalid JSON in " + file + ": " + e.getMessage(), e);
		}
		return parse(json);
	}

	static Loaded parse(JsonObject json) {
		ValueReader reader = new ValueReader(json);
		MeowConfig config = new MeowConfig(
				reader.set("ignoredActions", name -> ActionType.fromKey(name.toLowerCase(Locale.ROOT))),
				reader.set("ignoredWorlds", MeowConfig::identifier),
				reader.set("ignoredSources", MeowConfig::identifier),
				reader.set("ignoredObjects", MeowConfig::identifier),
				reader.bool("logHostileMobKills", DEFAULTS.logHostileMobKills),
				reader.integer("retentionDays", DEFAULTS.retentionDays, 0, 36_500),
				reader.integer("maxRollbackActions", DEFAULTS.maxRollbackActions, 1, 10_000_000),
				reader.integer("rollbackTickBudgetMillis", DEFAULTS.rollbackTickBudgetMillis, 1, 45),
				reader.integer("lookupPageSize", DEFAULTS.lookupPageSize, 1, 50),
				reader.integer("maxRadius", DEFAULTS.maxRadius, 1, 10_000));
		return new Loaded(config, List.copyOf(reader.warnings));
	}

	/**
	 * Whether {@code record} should be logged. A block change is ignored by the block it is about
	 * (the broken block, or the placed one); an item action by its item or container.
	 */
	public boolean shouldLog(ActionRecord record) {
		if (ignoredActions.contains(record.type())
				|| ignoredSources.contains(record.source())
				|| !ignoredWorlds.isEmpty() && ignoredWorlds.contains(record.world().identifier().toString())) {
			return false;
		}
		if (ignoredObjects.isEmpty()) {
			return true;
		}
		return switch (record.type().kind()) {
			case BLOCK -> !ignoredObjects.contains(blockId(record.type() == ActionType.BLOCK_BREAK ? record.oldState() : record.newState()));
			case ITEM -> !ignoredObjects.contains(record.object()) && !ignoredObjects.contains(blockId(record.oldState()));
			case ENTITY -> !ignoredObjects.contains(record.object());
		};
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	/** Adds the default namespace, so {@code stone} matches {@code minecraft:stone}. */
	private static Optional<String> identifier(String text) {
		return Optional.ofNullable(Identifier.tryParse(text.toLowerCase(Locale.ROOT))).map(Identifier::toString);
	}

	/** Reads typed values from the JSON, collecting a warning for each one that is invalid. */
	private static final class ValueReader {
		private final JsonObject json;
		private final List<String> warnings = new ArrayList<>();

		ValueReader(JsonObject json) {
			this.json = json;
		}

		<T> Set<T> set(String key, Function<String, Optional<T>> parser) {
			JsonElement element = json.get(key);
			if (element == null || element.isJsonNull()) {
				return Set.of();
			}
			List<JsonElement> values = element.isJsonArray() ? element.getAsJsonArray().asList() : List.of(element);
			Set<T> result = new HashSet<>();
			for (JsonElement value : values) {
				Optional<T> parsed = value instanceof JsonPrimitive primitive && primitive.isString()
						? parser.apply(primitive.getAsString())
						: Optional.empty();
				parsed.ifPresentOrElse(result::add, () -> warnings.add(key + ": ignoring invalid entry " + value));
			}
			return result;
		}

		boolean bool(String key, boolean fallback) {
			JsonElement element = json.get(key);
			if (element == null) {
				return fallback;
			}
			if (element instanceof JsonPrimitive primitive && primitive.isBoolean()) {
				return primitive.getAsBoolean();
			}
			warnings.add(key + ": expected true or false, got " + element + "; using " + fallback);
			return fallback;
		}

		int integer(String key, int fallback, int min, int max) {
			JsonElement element = json.get(key);
			if (element == null) {
				return fallback;
			}
			if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
				double value = primitive.getAsDouble();
				if (value == Math.rint(value) && value >= min && value <= max) {
					return (int) value;
				}
			}
			warnings.add(key + ": expected a whole number from " + min + " to " + max + ", got " + element + "; using " + fallback);
			return fallback;
		}
	}
}
