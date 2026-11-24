package zakyatto.meowlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.BlockCapture;
import zakyatto.meowlogger.capture.FireCapture;

/** Logs blocks fire burns, crediting whoever lit the fire, and lets new fire blocks inherit that player. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
	@WrapMethod(method = "tick")
	private void meowlogger$trackIgniter(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
		FireCapture.beginTick(level, pos);
		try {
			original.call(state, level, pos, random);
		} finally {
			FireCapture.endTick(level, pos);
		}
	}

	@WrapMethod(method = "checkBurnOut")
	private void meowlogger$logBurn(Level level, BlockPos pos, int chance, RandomSource random, int age, Operation<Void> original) {
		if (level.isClientSide()) {
			original.call(level, pos, chance, random, age);
			return;
		}
		BlockState oldState = level.getBlockState(pos);
		CompoundTag oldData = oldState.hasBlockEntity() ? BlockCapture.save(level, level.getBlockEntity(pos)) : null;
		original.call(level, pos, chance, random, age);
		FireCapture.logBurn(level, pos, oldState, oldData);
	}
}
