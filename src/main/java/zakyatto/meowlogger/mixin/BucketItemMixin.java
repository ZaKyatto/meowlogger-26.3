package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import zakyatto.meowlogger.action.ActionType;
import zakyatto.meowlogger.capture.BlockCapture;

@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
	// emptyContents calls itself for the block in front when the clicked block cannot hold the fluid;
	// only the call whose position actually changed logs anything.
	@WrapMethod(method = "emptyContents")
	private boolean meowlogger$logEmpty(LivingEntity user, Level level, BlockPos pos, BlockHitResult hit, Operation<Boolean> original) {
		if (!(user instanceof ServerPlayer player)) {
			return original.call(user, level, pos, hit);
		}
		BlockState oldState = level.getBlockState(pos);
		boolean emptied = original.call(user, level, pos, hit);
		BlockState newState = level.getBlockState(pos);
		if (emptied && newState != oldState) {
			BlockCapture.logPlayerChange(ActionType.BLOCK_PLACE, player, level, pos, oldState, newState);
		}
		return emptied;
	}

	@WrapOperation(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack meowlogger$logFill(BucketPickup pickup, LivingEntity user, LevelAccessor level, BlockPos pos, BlockState oldState,
			Operation<ItemStack> original) {
		ItemStack filled = original.call(pickup, user, level, pos, oldState);
		if (!filled.isEmpty() && user instanceof ServerPlayer player && level instanceof Level serverLevel) {
			BlockState newState = serverLevel.getBlockState(pos);
			if (newState != oldState) {
				BlockCapture.logPlayerChange(ActionType.BLOCK_BREAK, player, serverLevel, pos, oldState, newState);
			}
		}
		return filled;
	}
}
