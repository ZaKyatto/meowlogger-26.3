package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.DecorationCapture;

/** Logs players putting items into, taking them out of, or rotating item frames, and swapping armor stand equipment. */
@Mixin({ItemFrame.class, ArmorStand.class})
abstract class DecorationInteractMixin {
	@WrapMethod(method = "interact")
	private InteractionResult meowlogger$logChange(Player player, InteractionHand hand, Vec3 location, Operation<InteractionResult> original) {
		Entity self = (Entity) (Object) this;
		if (self.level().isClientSide()) {
			return original.call(player, hand, location);
		}
		CompoundTag before = DecorationCapture.beforeInteract(self);
		InteractionResult result = original.call(player, hand, location);
		DecorationCapture.afterInteract(player, self, before);
		return result;
	}
}
