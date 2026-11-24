package zakyatto.meowlogger.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import zakyatto.meowlogger.capture.BlockCapture;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@WrapMethod(method = "interactWithBlocks")
	private void meowlogger$logExplodedBlocks(List<BlockPos> positions, Operation<Void> original) {
		BlockCapture.Snapshot snapshot = BlockCapture.snapshot(level, positions);
		original.call(positions);
		BlockCapture.logExplosion((ServerExplosion) (Object) this, snapshot);
	}
}
