package zakyatto.meowlogger.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import org.spongepowered.asm.mixin.Mixin;

import zakyatto.meowlogger.capture.ContainerCapture;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@WrapMethod(method = "clicked")
	private void meowlogger$logContainerChanges(int slot, int button, ContainerInput input, Player player, Operation<Void> original) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			original.call(slot, button, input, player);
			return;
		}
		List<ContainerCapture.Tracked> snapshot = ContainerCapture.snapshot((AbstractContainerMenu) (Object) this);
		original.call(slot, button, input, player);
		if (!snapshot.isEmpty()) {
			ContainerCapture.logChanges(serverPlayer, snapshot);
		}
	}
}
