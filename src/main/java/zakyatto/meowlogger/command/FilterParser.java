package zakyatto.meowlogger.command;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.query.ActionFilter;

/**
 * Parses lookup parameters such as {@code radius:10 player:Steve,Alex source:tnt action:block-break time:2h30m}
 * into an {@link ActionFilter}, and suggests completions for them.
 */
public final class FilterParser {
	private static final List<String> KEYS = List.of("radius:", "player:", "source:", "action:", "block:", "item:", "entity:", "time:", "before:", "world:");
	private static final Pattern DURATION_PART = Pattern.compile("(\\d{1,9})([smhdw])");

	private static final DynamicCommandExceptionType UNKNOWN_KEY = error(key -> "Unknown parameter '" + key + "'. Use one of: radius, player, source, action, block, item, entity, time, before, world");
	private static final DynamicCommandExceptionType MALFORMED = error(token -> "Expected key:value, got '" + token + "'");
	private static final Dynamic2CommandExceptionType BAD_RADIUS = new Dynamic2CommandExceptionType((value, max) ->
			Component.literal("Radius must be a whole number from 0 to " + max + ", got '" + value + "'"));
	private static final DynamicCommandExceptionType BAD_TIME = error(value -> "Invalid time '" + value + "'. Use e.g. 30m, 2h, 1d12h (units: s, m, h, d, w)");
	private static final DynamicCommandExceptionType UNKNOWN_ACTION = error(value -> "Unknown action '" + value + "'");
	private static final DynamicCommandExceptionType UNKNOWN_BLOCK = error(value -> "Unknown block '" + value + "'");
	private static final DynamicCommandExceptionType UNKNOWN_ITEM = error(value -> "Unknown item '" + value + "'");
	private static final DynamicCommandExceptionType UNKNOWN_ENTITY = error(value -> "Unknown entity '" + value + "'");
	private static final DynamicCommandExceptionType BAD_SOURCE = error(value -> "Invalid source '" + value + "'");
	private static final List<String> COMMON_SOURCES = List.of("player", "tnt", "creeper", "fire", "end_crystal", "tnt_minecart", "fireball", "wither", "bad_respawn_point",
			"arrow", "lava", "fall", "drown", "zombie", "skeleton", "wolf");
	private static final DynamicCommandExceptionType BAD_WORLD = error(value -> "Invalid world '" + value + "'");

	private FilterParser() {
	}

	/**
	 * Parses {@code input}.
	 *
	 * @param sourceWorld dimension of the command source; used for {@code radius} unless {@code world} is given
	 * @param sourcePos   position of the command source; the center for {@code radius}
	 * @param nowMillis   current time, used to resolve {@code time} and {@code before}
	 * @param maxRadius   largest allowed {@code radius}
	 */
	public static ActionFilter parse(String input, ResourceKey<Level> sourceWorld, BlockPos sourcePos, long nowMillis, int maxRadius)
			throws CommandSyntaxException {
		ResourceKey<Level> world = null;
		int radius = -1;
		long sinceMillis = 0;
		long beforeMillis = 0;
		Set<String> players = new HashSet<>();
		Set<ActionType> types = new HashSet<>();
		Set<String> sources = new HashSet<>();
		Set<String> objects = new HashSet<>();

		for (String token : input.trim().split("\\s+")) {
			int colon = token.indexOf(':');
			if (colon <= 0 || colon == token.length() - 1) {
				throw MALFORMED.create(token);
			}
			String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
			String value = token.substring(colon + 1);
			switch (key) {
				case "radius", "r" -> radius = parseRadius(value, maxRadius);
				case "player", "p" -> players.addAll(Arrays.asList(value.split(",")));
				case "action", "a" -> {
					for (String name : value.split(",")) {
						types.add(ActionType.fromKey(name.toLowerCase(Locale.ROOT)).orElseThrow(() -> UNKNOWN_ACTION.create(name)));
					}
				}
				case "source", "s" -> {
					for (String name : value.split(",")) {
						Identifier id = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
						if (id == null) {
							throw BAD_SOURCE.create(name);
						}
						sources.add(id.toString());
					}
				}
				case "block", "b" -> addRegistered(value, BuiltInRegistries.BLOCK, UNKNOWN_BLOCK, objects);
				case "item", "i" -> addRegistered(value, BuiltInRegistries.ITEM, UNKNOWN_ITEM, objects);
				case "entity", "e" -> addRegistered(value, BuiltInRegistries.ENTITY_TYPE, UNKNOWN_ENTITY, objects);
				case "time", "t" -> sinceMillis = nowMillis - parseDuration(value);
				case "before" -> beforeMillis = nowMillis - parseDuration(value);
				case "world", "w" -> {
					Identifier id = Identifier.tryParse(value.toLowerCase(Locale.ROOT));
					if (id == null) {
						throw BAD_WORLD.create(value);
					}
					world = ResourceKey.create(Registries.DIMENSION, id);
				}
				default -> throw UNKNOWN_KEY.create(key);
			}
		}
		players.remove("");

		BlockPos min = null;
		BlockPos max = null;
		if (radius >= 0) {
			if (world == null) {
				world = sourceWorld;
			}
			min = sourcePos.offset(-radius, -radius, -radius);
			max = sourcePos.offset(radius, radius, radius);
		}
		return new ActionFilter(world, min, max, players, sources, types, objects, Math.max(sinceMillis, 0), Math.max(beforeMillis, 0), null);
	}

	/** Adds each comma-separated identifier in {@code value} to {@code into}, requiring it to exist in {@code registry}. */
	private static void addRegistered(String value, Registry<?> registry, DynamicCommandExceptionType unknown, Set<String> into)
			throws CommandSyntaxException {
		for (String name : value.split(",")) {
			Identifier id = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
			if (id == null || !registry.containsKey(id)) {
				throw unknown.create(name);
			}
			into.add(id.toString());
		}
	}

	private static int parseRadius(String value, int maxRadius) throws CommandSyntaxException {
		try {
			int radius = Integer.parseInt(value);
			if (radius >= 0 && radius <= maxRadius) {
				return radius;
			}
		} catch (NumberFormatException ignored) {
			// reported below
		}
		throw BAD_RADIUS.create(value, maxRadius);
	}

	/** Parses durations like {@code 1d12h} into milliseconds. */
	static long parseDuration(String input) throws CommandSyntaxException {
		String value = input.toLowerCase(Locale.ROOT);
		Matcher matcher = DURATION_PART.matcher(value);
		long total = 0;
		int end = 0;
		while (matcher.lookingAt()) {
			long unitMillis = switch (matcher.group(2)) {
				case "s" -> 1_000L;
				case "m" -> 60_000L;
				case "h" -> 3_600_000L;
				case "d" -> 86_400_000L;
				default -> 604_800_000L;
			};
			try {
				total = Math.addExact(total, Math.multiplyExact(Long.parseLong(matcher.group(1)), unitMillis));
			} catch (ArithmeticException e) {
				throw BAD_TIME.create(input);
			}
			end = matcher.end();
			matcher.region(end, value.length());
		}
		if (end == 0 || end != value.length()) {
			throw BAD_TIME.create(input);
		}
		return total;
	}

	/** Suggests completions for the token under the cursor. {@code builder} must start at the beginning of the parameters. */
	public static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, SharedSuggestionProvider source) {
		String input = builder.getRemaining();
		int tokenStart = input.lastIndexOf(' ') + 1;
		String token = input.substring(tokenStart);
		int colon = token.indexOf(':');
		if (colon < 0) {
			return SharedSuggestionProvider.suggest(KEYS, builder.createOffset(builder.getStart() + tokenStart));
		}
		int valueStart = Math.max(colon, token.lastIndexOf(',')) + 1;
		SuggestionsBuilder value = builder.createOffset(builder.getStart() + tokenStart + valueStart);
		return switch (token.substring(0, colon).toLowerCase(Locale.ROOT)) {
			case "player", "p" -> SharedSuggestionProvider.suggest(source.getOnlinePlayerNames(), value);
			case "action", "a" -> SharedSuggestionProvider.suggest(Arrays.stream(ActionType.values()).map(ActionType::key), value);
			case "block", "b" -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), value);
			case "item", "i" -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), value);
			case "entity", "e" -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), value);
			case "world", "w" -> SharedSuggestionProvider.suggestResource(source.levels().stream().map(ResourceKey::identifier), value);
			case "time", "t", "before" -> SharedSuggestionProvider.suggest(List.of("30m", "1h", "12h", "1d", "7d"), value);
			case "radius", "r" -> SharedSuggestionProvider.suggest(List.of("5", "10", "25", "50"), value);
			case "source", "s" -> SharedSuggestionProvider.suggest(COMMON_SOURCES, value);
			default -> value.buildFuture();
		};
	}

	private static DynamicCommandExceptionType error(Function<String, String> message) {
		return new DynamicCommandExceptionType(value -> Component.literal(message.apply((String) value)));
	}
}
