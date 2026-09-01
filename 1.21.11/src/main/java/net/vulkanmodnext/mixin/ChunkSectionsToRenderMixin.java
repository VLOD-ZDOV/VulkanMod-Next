package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.vulkanmodnext.client.TerrainFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the world's terrain is drawn, and where this mod takes it over.
 *
 * <p>The equivalent of 1.12.2's {@code renderBlockLayer} and 1.16.5's
 * {@code renderChunkLayer}, with one difference that shapes the port: it is
 * called three times a frame, not five, and each call is a <em>group</em> of
 * layers drawn into one render target. See {@code TerrainFrame} for how the
 * group is split back into the single layers the renderer underneath expects.
 *
 * <p>Cancelled at the head, before the game opens its render pass. That is the
 * right side of it here, and the opposite of where the 1.16.5 hook sits: there,
 * cancelling before the layer's OpenGL setup meant compositing under somebody
 * else's state. Here there is no state to inherit — the pass would create its
 * own framebuffer binding and its own pipeline — so anything set up before
 * cancelling would simply be thrown away, and the composite binds the target it
 * needs for itself.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$group(ChunkSectionLayerGroup group, GpuSampler sampler,
                                     CallbackInfo ci) {
        if (TerrainFrame.group(group)) {
            ci.cancel();
        }
    }
}
