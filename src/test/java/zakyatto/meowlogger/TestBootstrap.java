package zakyatto.meowlogger;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Sets up enough of Minecraft for unit tests: the built-in registries, plus the default item
 * components that a server normally binds while loading data (creating an {@code ItemStack} fails
 * without them).
 */
public final class TestBootstrap {
	private static HolderLookup.Provider registries;

	private TestBootstrap() {
	}

	/** Bootstraps once and returns the vanilla registries, including data-driven ones such as enchantments. */
	public static synchronized HolderLookup.Provider registries() {
		if (registries == null) {
			SharedConstants.tryDetectVersion();
			Bootstrap.bootStrap();
			registries = VanillaRegistries.createWorldLookup();
			BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);
		}
		return registries;
	}
}
