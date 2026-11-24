package zakyatto.meowlogger.capture;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import zakyatto.meowlogger.action.ActionRecord;
import zakyatto.meowlogger.action.ActionType;

/**
 * Tracks the player whose right-click is being handled, and logs what that click did to the block
 * it targeted: a book put on a lectern, a disc into a jukebox, a plant into a flower pot, a log
 * stripped, dirt tilled. Container menus (chests, furnaces) are logged by {@link ContainerCapture}
 * instead, and only changes of block or block entity data count, so opening doors or flipping
 * levers is not logged. Server thread only.
 */
public final class InteractionCapture {
	/** The block a player's right-click targeted, as it was before the click. */
	public static final class Use {
		private final ServerPlayer player;
		private final Level level;
		private final BlockPos pos;
		private final BlockState state;
		private final CompoundTag data;
		private final AbstractContainerMenu menu;
		private final Use outer;
		private boolean logged;

		private Use(ServerPlayer player, Level level, BlockPos pos, Use outer) {
			this.player = player;
			this.level = level;
			this.pos = pos;
			this.state = pos == null ? null : level.getBlockState(pos);
			this.data = pos == null ? null : contents(level, pos);
			this.menu = player.containerMenu;
			this.outer = outer;
		}
	}

	private record MenuBlock(Level level, BlockPos pos) {
	}

	private static Use current;
	/** Lecterns whose menus are open, so taking the book can be logged at the right position. */
	private static final Map<AbstractContainerMenu, MenuBlock> lecternMenus = new WeakHashMap<>();

	private InteractionCapture() {
	}

	/** The player whose right-click is being handled, or null. Placements and fires happening now are theirs. */
	public static ServerPlayer actingPlayer() {
		return current == null ? null : current.player;
	}

	/**
	 * Called before a player's right-click is handled.
	 *
	 * @param pos the block clicked, or null when using an item in the air
	 * @return the state to pass to {@link #afterUse}
	 */
	public static Use beforeUse(ServerPlayer player, Level level, BlockPos pos) {
		current = new Use(player, level, pos == null ? null : pos.immutable(), current);
		return current;
	}

	/** Called after the right-click, with what {@link #beforeUse} returned. */
	public static void afterUse(Use use) {
		current = use.outer;
		if (use.pos == null) {
			return;
		}
		if (!use.logged) {
			logIfChanged(use.player, use.level, use.pos, use.state, use.data);
		}
		if (use.player.containerMenu != use.menu && use.player.containerMenu instanceof LecternMenu menu) {
			lecternMenus.put(menu, new MenuBlock(use.level, use.pos));
		}
	}

	/** Called when a block change at {@code pos} was logged elsewhere (a placement or bucket), so the click isn't logged twice. */
	static void markLogged(BlockPos pos) {
		for (Use use = current; use != null; use = use.outer) {
			if (pos.equals(use.pos)) {
				use.logged = true;
			}
		}
	}

	/** Called before a lectern menu button is handled; returns the lectern's state to pass to {@link #afterLecternButton}, or null. */
	public static Use beforeLecternButton(ServerPlayer player, LecternMenu menu) {
		MenuBlock lectern = lecternMenus.get(menu);
		return lectern == null ? null : new Use(player, lectern.level(), lectern.pos(), null);
	}

	public static void afterLecternButton(Use use) {
		if (use != null) {
			logIfChanged(use.player, use.level, use.pos, use.state, use.data);
		}
	}

	private static void logIfChanged(ServerPlayer player, Level level, BlockPos pos, BlockState oldState, CompoundTag oldData) {
		BlockState newState = level.getBlockState(pos);
		CompoundTag newData = contents(level, pos);
		if (newState.getBlock() != oldState.getBlock() || !Objects.equals(oldData, newData)) {
			BlockCapture.log(ActionType.BLOCK_CHANGE, ActionRecord.PLAYER_SOURCE, player, level, pos, oldState, oldData, newState, newData);
		}
	}

	/** Block entity data, unless the block has none or is a container with a menu (whose contents {@link ContainerCapture} logs). */
	private static CompoundTag contents(Level level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity == null || blockEntity instanceof BaseContainerBlockEntity ? null : BlockCapture.save(level, blockEntity);
	}
}
