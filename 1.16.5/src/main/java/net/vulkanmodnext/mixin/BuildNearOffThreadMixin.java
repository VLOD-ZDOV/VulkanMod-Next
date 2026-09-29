package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.WorldRenderer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Whether a chunk that has just changed near the camera is queued for a
 * builder thread rather than built here and now.
 *
 * Vanilla builds it here: any dirty chunk whose centre is within about 28
 * blocks of the eye, and any chunk the player changed, is rebuilt on the render
 * thread in the middle of setting the frame up, and the frame waits for it. On
 * 1.12.2 that measured 0.4 to 0.7 ms a frame in the windows where it happens,
 * which was nearly all of what that step cost, and it is why breaking a block
 * can be felt rather than only seen. 1.16.5 has the same two branches.
 *
 * Forge already has a switch for this, {@code alwaysSetupTerrainOffThread} in
 * its client config, so the behaviour is well travelled rather than invented
 * here; this one lets it be reached from this mod's screen, and leaves Forge's
 * own setting winning when it is on. The price is that a chunk you just
 * changed is a frame or two behind instead of instant.
 *
 * <h2>Both places, or neither</h2>
 *
 * The flag is read twice. {@code setupRender} decides whether a nearby dirty
 * chunk is built on the spot or put in the queue, and {@code compileChunksUntil}
 * then works through that queue and builds a chunk the player changed on the
 * spot after all. Answering only the first would move the synchronous build
 * from one method to the other and save nothing.
 */
@Mixin(WorldRenderer.class)
public abstract class BuildNearOffThreadMixin {

    // remap = false on the target: ForgeConfigSpec is Forge's own class and has
    // no obfuscated name to look up; the method names around it do. require = 2
    // because one landing and one missing is the failure described above.
    @Redirect(method = {"setupRender", "compileChunksUntil"}, require = 2,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeConfigSpec$BooleanValue;"
                            + "get()Ljava/lang/Object;"))
    private Object vulkanmodnext$buildNearOffThread(ForgeConfigSpec.BooleanValue forge) {
        Object forgeSays = forge.get();
        if (Boolean.TRUE.equals(forgeSays)) {
            return forgeSays;
        }
        return VulkanConfig.on("buildNearOffThread") ? Boolean.TRUE : forgeSays;
    }
}
