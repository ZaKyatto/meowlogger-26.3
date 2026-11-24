package zakyatto.meowlogger.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.query.ActionFilter;
import zakyatto.meowlogger.rollback.Rollbacks;

/**
 * The {@code /meowlogger} command (alias {@code /ml}). When no permission mod sets them, the
 * permission nodes {@code meowlogger:lookup}, {@code inspect}, {@code rollback} and {@code teleport}
 * default to operator level 2, {@code meowlogger:reload} to level 3 and {@code meowlogger:purge} to level 4.
 */
public final class MeowCommands {
	private static final Predicate<CommandSourceStack> CAN_LOOKUP = permission("lookup");
	private static final Predicate<CommandSourceStack> CAN_INSPECT = permission("inspect");
	private static final Predicate<CommandSourceStack> CAN_ROLLBACK = permission("rollback");
	private static final Predicate<CommandSourceStack> CAN_TELEPORT = permission("teleport");
	private static final Predicate<CommandSourceStack> CAN_RELOAD = permission("reload", PermissionLevel.ADMINS);
	private static final Predicate<CommandSourceStack> CAN_PURGE = permission("purge", PermissionLevel.OWNERS);
	private static final Predicate<CommandSourceStack> CAN_ANY = CAN_LOOKUP.or(CAN_INSPECT).or(CAN_ROLLBACK).or(CAN_RELOAD).or(CAN_PURGE);

	/**
	 * The vanilla client asks for confirmation before a chat click runs a command whose path contains a
	 * node that the server's permission-less, server-less probe source cannot use. Letting that probe
	 * through keeps the page and teleport links confirmation-free; real sources always have a server,
	 * so they are still held to the permission check.
	 */
	private static final Predicate<CommandSourceStack> CLICK_PROBE = source -> source.getServer() == null;

	private static final Component LOOKUP_TITLE = Component.literal("Lookup");
	private static final SimpleCommandExceptionType UNSCOPED = new SimpleCommandExceptionType(
			Component.literal("Rollback and restore need a radius: or player: parameter"));
	private static final SimpleCommandExceptionType PURGE_EVERYTHING = new SimpleCommandExceptionType(
			Component.literal("Purge needs a parameter that limits what is deleted, e.g. before:90d"));
	private static final SimpleCommandExceptionType NO_PERMISSION = new SimpleCommandExceptionType(
			Component.literal("You do not have permission to do that"));

	private MeowCommands() {
	}

	/** Registers the commands. Call once from the mod initializer. */
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralCommandNode<CommandSourceStack> root = dispatcher.register(literal("meowlogger")
				.requires(CLICK_PROBE.or(CAN_ANY))
				.then(inspect("inspect"))
				.then(inspect("i"))
				.then(withParameters("lookup", CAN_LOOKUP, MeowCommands::lookup))
				.then(withParameters("l", CAN_LOOKUP, MeowCommands::lookup))
				.then(withParameters("rollback", CAN_ROLLBACK, (source, parameters) -> rollback(source, parameters, false)))
				.then(withParameters("restore", CAN_ROLLBACK, (source, parameters) -> rollback(source, parameters, true)))
				.then(withParameters("purge", CAN_PURGE, MeowCommands::purge)
						.then(literal("confirm").executes(context -> {
							Purges.confirm(context.getSource());
							return 1;
						})))
				.then(literal("reload").requires(CAN_RELOAD).executes(context -> reload(context.getSource())))
				.then(literal("page")
						.requires(CLICK_PROBE.or(CAN_LOOKUP).or(CAN_INSPECT))
						.then(argument("page", IntegerArgumentType.integer(1))
								.executes(context -> page(context.getSource(), IntegerArgumentType.getInteger(context, "page")))))
				.then(literal("teleport")
						.requires(CLICK_PROBE.or(CAN_TELEPORT))
						.then(argument("world", DimensionArgument.dimension())
								.then(argument("pos", BlockPosArgument.blockPos())
										.executes(MeowCommands::teleport)))));
		dispatcher.register(literal("ml").requires(root.getRequirement()).redirect(root));
	}

	private interface ParameterCommand {
		void run(CommandSourceStack source, String parameters) throws CommandSyntaxException;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> inspect(String name) {
		return literal(name).requires(CAN_INSPECT).executes(MeowCommands::toggleInspector);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> withParameters(String name, Predicate<CommandSourceStack> requirement,
			ParameterCommand command) {
		return literal(name).requires(requirement)
				.then(argument("parameters", StringArgumentType.greedyString())
						.suggests((context, builder) -> FilterParser.suggest(builder, context.getSource()))
						.executes(context -> {
							command.run(context.getSource(), StringArgumentType.getString(context, "parameters"));
							return 1;
						}));
	}

	private static int toggleInspector(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		Component message = Inspector.toggle(player)
				? Component.literal("Inspector enabled: left-click a block to see its history, right-click a face to see the block in front of it")
				: Component.literal("Inspector disabled");
		context.getSource().sendSystemMessage(message);
		return 1;
	}

	private static ActionFilter parse(CommandSourceStack source, String parameters) throws CommandSyntaxException {
		return FilterParser.parse(parameters, source.getLevel().dimension(),
				BlockPos.containing(source.getPosition()), System.currentTimeMillis(), MeowLogger.config().maxRadius());
	}

	private static void lookup(CommandSourceStack source, String parameters) throws CommandSyntaxException {
		Lookups.show(source, parse(source, parameters), 1, LOOKUP_TITLE);
	}

	private static void rollback(CommandSourceStack source, String parameters, boolean restore) throws CommandSyntaxException {
		ActionFilter filter = parse(source, parameters);
		if (!filter.isScoped()) {
			throw UNSCOPED.create();
		}
		Rollbacks.start(source, filter, parameters.trim(), restore);
	}

	private static void purge(CommandSourceStack source, String parameters) throws CommandSyntaxException {
		ActionFilter filter = parse(source, parameters);
		if (filter.matchesEverything()) {
			throw PURGE_EVERYTHING.create();
		}
		Purges.request(source, filter, parameters.trim());
	}

	private static int reload(CommandSourceStack source) {
		List<String> warnings;
		try {
			warnings = MeowLogger.reloadConfig();
		} catch (IOException e) {
			MeowLogger.LOGGER.warn("Failed to reload the config: {}", e.getMessage());
			source.sendFailure(Component.literal("Could not reload the config, keeping the current one: " + e.getMessage()));
			return 0;
		}
		if (warnings.isEmpty()) {
			source.sendSystemMessage(Component.literal("Reloaded the MeowLogger config").withStyle(ChatFormatting.GREEN));
		} else {
			Component details = Component.literal(String.join("\n", warnings));
			source.sendSystemMessage(Component.literal("Reloaded the MeowLogger config with " + warnings.size()
					+ (warnings.size() == 1 ? " problem" : " problems") + "; invalid values use their defaults").withStyle(style -> style
					.withColor(ChatFormatting.YELLOW)
					.withHoverEvent(new HoverEvent.ShowText(details))));
		}
		return 1;
	}

	private static int page(CommandSourceStack source, int page) throws CommandSyntaxException {
		requireAtRuntime(source, CAN_LOOKUP.or(CAN_INSPECT));
		if (!Lookups.page(source, page)) {
			source.sendFailure(Component.literal("No previous lookup to page through"));
			return 0;
		}
		return 1;
	}

	private static int teleport(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		requireAtRuntime(source, CAN_TELEPORT);
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = DimensionArgument.getDimension(context, "world");
		BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
		player.teleportTo(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
		return 1;
	}

	/** Re-checks a permission when the command runs, since {@link #CLICK_PROBE} also lets server-less sources parse it. */
	private static void requireAtRuntime(CommandSourceStack source, Predicate<CommandSourceStack> permission) throws CommandSyntaxException {
		if (!permission.test(source)) {
			throw NO_PERMISSION.create();
		}
	}

	private static Predicate<CommandSourceStack> permission(String name) {
		return permission(name, PermissionLevel.GAMEMASTERS);
	}

	private static Predicate<CommandSourceStack> permission(String name, PermissionLevel defaultLevel) {
		return PermissionPredicates.require(MeowLogger.id(name), defaultLevel);
	}
}
