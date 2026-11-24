package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.ItemEntityCapture;

/** Logs players picking items up. */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
	@WrapMethod(method = "playerTouch")
	private void meowlogger$logPickup(Player player, Operation<Void> original) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (self.level().isClientSide() || self.hasPickUpDelay()) {
			original.call(player);
			return;
		}
		ItemStack before = self.getItem().copy();
		original.call(player);
		ItemEntityCapture.afterTouch(player, self, before);
	}
}
