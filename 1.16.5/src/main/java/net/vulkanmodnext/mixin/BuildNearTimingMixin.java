package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.vulkanmodnext.client.CpuSavings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts and times the chunk builds the render thread does itself.
 *
 * This is the number {@link BuildNearOffThreadMixin} is meant to take to zero:
 * with the switch on and Forge's own flag off, nothing but this mod's switch
 * stands between a broken block and a build here, so a count that stays above
 * zero says the redirect did not land. Both vanilla call sites go through this
 * one method, which is why it is timed here rather than at either of them.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class BuildNearTimingMixin {

    @Unique
    private long vulkanmodnext$syncStarted;

    @Inject(method = "rebuildChunkSync", at = @At("HEAD"))
    private void vulkanmodnext$syncBegins(ChunkRenderDispatcher.ChunkRender chunk, CallbackInfo ci) {
        this.vulkanmodnext$syncStarted = System.nanoTime();
    }

    @Inject(method = "rebuildChunkSync", at = @At("RETURN"))
    private void vulkanmodnext$syncEnds(ChunkRenderDispatcher.ChunkRender chunk, CallbackInfo ci) {
        CpuSavings.countSyncBuild(System.nanoTime() - this.vulkanmodnext$syncStarted);
    }
}
