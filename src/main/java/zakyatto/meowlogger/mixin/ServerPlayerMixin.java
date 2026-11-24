package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.ItemEntityCapture;

/** Logs players dropping items: thrown, dropped from a menu, or spilled on death. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@WrapMethod(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;")
	private ItemEntity meowlogger$logDrop(ItemStack stack, boolean throwRandomly, Prediction prediction, Operation<ItemEntity> original) {
		ItemEntity entity = original.call(stack, throwRandomly, prediction);
		ItemEntityCapture.dropped((ServerPlayer) (Object) this, entity);
		return entity;
	}
}
