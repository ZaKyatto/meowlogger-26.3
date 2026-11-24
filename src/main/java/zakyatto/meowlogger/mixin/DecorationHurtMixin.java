package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.VehicleEntity;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.DecorationCapture;

/** Logs decorations and vehicles broken (or item frames emptied) by damage: players, projectiles, explosions, fire. */
@Mixin({BlockAttachedEntity.class, ItemFrame.class, ArmorStand.class, VehicleEntity.class})
abstract class DecorationHurtMixin {
	@WrapMethod(method = "hurtServer")
	private boolean meowlogger$logBreak(ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
		Entity self = (Entity) (Object) this;
		CompoundTag before = DecorationCapture.beforeHurt(self);
		try {
			return original.call(level, source, amount);
		} finally {
			DecorationCapture.afterHurt(self, source, before);
		}
	}
}
