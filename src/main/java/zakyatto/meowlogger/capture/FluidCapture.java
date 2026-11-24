package zakyatto.meowlogger.capture;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;

import zakyatto.meowlogger.action.ActionType;

/**
 * Logs water and lava flowing, washing blocks away, and turning into cobblestone, stone, obsidian or
 * basalt. Fluid a player placed (with a bucket, logged by the bucket hook) remembers them, and every
 * block it flows into inherits that, like fire does. Flowing into empty space is only logged for
 * such player-placed fluid, so natural lakes and rivers settling don't fill the log; washing blocks
 * away is always logged. Server thread only.
 */
public final class FluidCapture {
	/** The source of blocks fluids turn into stone or obsidian: lava meeting water. */
	public static final String SOLIDIFY_SOURCE = "minecraft:lava";

	private static final Lineage placers = new Lineage((level, pos) -> !level.getFluidState(pos).isEmpty(), 0);
	private static Lineage.Responsible ticking;
	private static boolean inFluidTick;

	private FluidCapture() {
	}

	/** Registers cleanup when the server stops. Call once from the mod initializer. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			placers.clear();
			ticking = null;
			inFluidTick = false;
		});
	}

	/** Called before the fluid at {@code pos} ticks (flows). */
	public static void beginTick(Level level, BlockPos pos) {
		ticking = placers.get(level, pos);
		inFluidTick = true;
	}

	/** Called after the fluid at {@code pos} ticked; forgets it if it is gone. */
	public static void endTick(Level level, BlockPos pos) {
		ticking = null;
		inFluidTick = false;
		if (level.getFluidState(pos).isEmpty()) {
			placers.remove(level, pos);
		}
	}

	/** Called when a liquid block appeared at {@code pos}, replacing {@code oldState} (a different block). */
	public static void liquidPlaced(Level level, BlockPos pos, BlockState state, BlockState oldState) {
		ServerPlayer player = InteractionCapture.actingPlayer();
		if (player != null) {
			placers.put(level, pos, Lineage.Responsible.of(player)); // the bucket hook logs the placement itself
			return;
		}
		if (!inFluidTick) {
			return; // a command, dispenser or another mod
		}
		Lineage.Responsible placer = ticking;
		if (placer != null) {
			placers.put(level, pos, placer);
		}
		String source = source(state.getFluidState().getType());
		if (!oldState.isAir()) {
			BlockCapture.log(ActionType.BLOCK_BREAK, source, placer, level, pos, oldState, null, state, null);
		} else if (placer != null) {
			BlockCapture.log(ActionType.BLOCK_PLACE, source, placer, level, pos, oldState, null, state, null);
		}
	}

	/** Called after fluid at {@code pos} may have turned into a solid block (lava meeting water); {@code before} is the state before. */
	public static void maybeSolidified(Level level, BlockPos pos, BlockState before) {
		BlockState after = level.getBlockState(pos);
		// Only fluid turning solid; lava spreading into air that then solidifies is logged by the inner call.
		if (before.getFluidState().isEmpty() || after.getBlock() == before.getBlock() || !after.getFluidState().isEmpty()) {
			return;
		}
		Lineage.Responsible placer = ticking != null ? ticking : placers.get(level, pos);
		for (int i = 0; placer == null && i < Direction.values().length; i++) {
			placer = placers.get(level, pos.relative(Direction.values()[i]));
		}
		placers.remove(level, pos);
		Lineage.Responsible responsible = placer;
		BlockPos at = pos.immutable();
		InteractionCapture.logAfterUse(() -> BlockCapture.log(ActionType.BLOCK_PLACE, SOLIDIFY_SOURCE, responsible, level, at,
				before, null, after, null));
	}

	/** {@code minecraft:water} or {@code minecraft:lava}, for still and flowing fluid alike. */
	private static String source(Fluid fluid) {
		Fluid still = fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
		return BuiltInRegistries.FLUID.getKey(still).toString();
	}
}
