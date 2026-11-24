package zakyatto.meowlogger.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the two halves of a double chest's combined container. */
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {
	@Accessor("container1")
	Container meowlogger$getContainer1();

	@Accessor("container2")
	Container meowlogger$getContainer2();
}
