package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.InteractionCapture;

/** Logs players taking the book off a lectern, which happens through the lectern's menu rather than a click on the block. */
@Mixin(LecternMenu.class)
abstract class LecternMenuMixin {
	@WrapMethod(method = "clickMenuButton")
	private boolean meowlogger$logTakeBook(Player player, int id, Operation<Boolean> original) {
		if (id != LecternMenu.BUTTON_TAKE_BOOK || !(player instanceof ServerPlayer serverPlayer)) {
			return original.call(player, id);
		}
		InteractionCapture.Use before = InteractionCapture.beforeLecternButton(serverPlayer, (LecternMenu) (Object) this);
		boolean result = original.call(player, id);
		InteractionCapture.afterLecternButton(before);
		return result;
	}
}
