package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.vulkanmodnext.client.MaterialRuns;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where a builder stops belonging to the chunk before it.
 *
 * The rebuild loop calls this the first time it writes into a layer, so it is
 * the one place a material table can be emptied without carrying the last
 * chunk's runs into this one. It is the twin of {@code preRenderBlocks} on
 * 1.12.2, and it lives on the outer class here, which is why it is a mixin of
 * its own rather than a third hook in {@link MaterialTagMixin}.
 */
@Mixin(ChunkRenderDispatcher.ChunkRender.class)
public abstract class MaterialLayerMixin {

    @Inject(method = "beginLayer", at = @At("HEAD"))
    private void vulkanmodnext$beginLayer(BufferBuilder builder, CallbackInfo ci) {
        if (VulkanConfig.on("materialTags")) {
            MaterialRuns.begin(builder);
        }
    }
}
