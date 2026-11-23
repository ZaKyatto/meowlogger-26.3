package zakyatto.meowlogger.command;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import zakyatto.meowlogger.query.ActionFilter;

/**
 * Inspector mode: while enabled, left-clicking a block shows its history and right-clicking a block
 * face shows the history of the block in front of it (useful for blocks that were broken). The
 * click itself is cancelled. Server thread only.
 */
public final class Inspector {
	private static final Set<UUID> inspecting = new HashSet<>();

	private Inspector() {
	}

	/** Registers the interaction listeners. Call once from the mod initializer. */
	public static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!isInspecting(player)) {
				return InteractionResult.PASS;
			}
			inspect((ServerPlayer) player, level, pos);
			return InteractionResult.FAIL;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!isInspecting(player)) {
				return InteractionResult.PASS;
			}
			// The client also sends an off-hand use when the main hand did nothing; ignore that duplicate.
			if (hand == InteractionHand.MAIN_HAND) {
				inspect((ServerPlayer) player, level, hit.getBlockPos().relative(hit.getDirection()));
			}
			// The vanilla client already predicted using its held item (e.g. placing a block), so resync its inventory.
			player.containerMenu.sendAllDataToRemote();
			return InteractionResult.FAIL;
		});
		// Vanilla can finish a break that started before inspect mode was enabled; never let one go through.
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !isInspecting(player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> inspecting.remove(handler.player.getUUID()));
	}

	/** Toggles inspector mode for {@code player} and returns whether it is now enabled. */
	public static boolean toggle(ServerPlayer player) {
		UUID id = player.getUUID();
		if (inspecting.remove(id)) {
			return false;
		}
		inspecting.add(id);
		return true;
	}

	// The instanceof check also keeps the client thread (singleplayer) away from the server-only set.
	private static boolean isInspecting(Player player) {
		return player instanceof ServerPlayer && inspecting.contains(player.getUUID());
	}

	private static void inspect(ServerPlayer player, Level level, BlockPos pos) {
		Lookups.show(player.createCommandSourceStack(), ActionFilter.at(level.dimension(), pos), 1,
				Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()));
	}
}
