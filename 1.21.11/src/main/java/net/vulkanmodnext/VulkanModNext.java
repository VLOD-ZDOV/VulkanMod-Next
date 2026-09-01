package net.vulkanmodnext;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Where the port starts. At this stage it does one thing: prove it is running.
 *
 * <p>The package is deliberately the same as the other two builds'. Eighteen
 * thousand lines of this project are the Vulkan half, which knows nothing about
 * Minecraft, and the whole point of a port is that they come across unchanged.
 * Nobody outside the jar ever sees a package name; the mod id, which they do
 * see, is separate and is not this.
 */
@Mod(VulkanModNext.MOD_ID)
public class VulkanModNext {

    public static final String MOD_ID = "vulkanmodnext";

    public static final Logger LOGGER = LogManager.getLogger("VulkanModNext");

    public VulkanModNext(FMLJavaModLoadingContext context) {
        LOGGER.info("VulkanMod 1.21.11 loaded.");
        net.vulkanmodnext.client.VulkanConfig.load(FMLPaths.CONFIGDIR.get().toFile());
        // What the whole port stands on, asked at the first possible moment.
        // If the game's own LWJGL cannot reach a Vulkan driver there is no
        // point in any of the rest, and finding that out here costs nothing.
        LOGGER.info("Vulkan bindings: {}", VulkanProbe.describe());
        net.vulkanmodnext.client.ClientTicks.subscribe();
    }
}
