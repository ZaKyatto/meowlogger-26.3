package zakyatto.meowlogger.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import zakyatto.meowlogger.MeowLogger;
import zakyatto.meowlogger.query.ActionFilter;

/**
 * The {@code /meowlogger} command (alias {@code /ml}). Permission nodes {@code meowlogger:lookup} and
 * {@code meowlogger:inspect} default to operator level 2 when no permission mod sets them.
 */
public final class MeowCommands {
	private static final Predicate<CommandSourceStack> CAN_LOOKUP =
			PermissionPredicates.require(MeowLogger.id("lookup"), PermissionLevel.GAMEMASTERS);
	private static final Predicate<CommandSourceStack> CAN_INSPECT =
			PermissionPredicates.require(MeowLogger.id("inspect"), PermissionLevel.GAMEMASTERS);
	private static final Component LOOKUP_TITLE = Component.literal("Lookup");

	private MeowCommands() {
	}

	/** Registers the commands. Call once from the mod initializer. */
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralCommandNode<CommandSourceStack> root = dispatcher.register(literal("meowlogger")
				.requires(CAN_LOOKUP.or(CAN_INSPECT))
				.then(inspect("inspect"))
				.then(inspect("i"))
				.then(lookup("lookup"))
				.then(lookup("l"))
				.then(literal("page")
						.requires(CAN_LOOKUP)
						.then(argument("page", IntegerArgumentType.integer(1))
								.executes(context -> page(context.getSource(), IntegerArgumentType.getInteger(context, "page"))))));
		dispatcher.register(literal("ml").requires(root.getRequirement()).redirect(root));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> inspect(String name) {
		return literal(name).requires(CAN_INSPECT).executes(MeowCommands::toggleInspector);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> lookup(String name) {
		return literal(name).requires(CAN_LOOKUP)
				.then(argument("parameters", StringArgumentType.greedyString())
						.suggests((context, builder) -> FilterParser.suggest(builder, context.getSource()))
						.executes(context -> lookup(context.getSource(), StringArgumentType.getString(context, "parameters"))));
	}

	private static int toggleInspector(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		Component message = Inspector.toggle(player)
				? Component.literal("Inspector enabled: left-click a block to see its history, right-click a face to see the block in front of it")
				: Component.literal("Inspector disabled");
		context.getSource().sendSystemMessage(message);
		return 1;
	}

	private static int lookup(CommandSourceStack source, String parameters) throws CommandSyntaxException {
		ActionFilter filter = FilterParser.parse(parameters, source.getLevel().dimension(),
				BlockPos.containing(source.getPosition()), System.currentTimeMillis());
		Lookups.show(source, filter, 1, LOOKUP_TITLE);
		return 1;
	}

	private static int page(CommandSourceStack source, int page) {
		if (!Lookups.page(source, page)) {
			source.sendFailure(Component.literal("No previous lookup to page through"));
			return 0;
		}
		return 1;
	}
}
