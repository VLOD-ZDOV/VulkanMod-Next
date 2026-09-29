package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.vulkanmodnext.client.ChunkBuildThreads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sizes the dispatcher's builder buffers to the configured thread count; the
 * threads themselves come from {@link ChunkBuilderExecutorMixin}. Why both,
 * and why the heap still has the last word: {@link ChunkBuildThreads}.
 *
 * The constructor with the count argument is Forge's; the one the renderer
 * calls passes -1 to it, which is the branch this lands in.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class ChunkBuildThreadsMixin {

    /**
     * The second {@code Math.min} in the constructor is {@code min(k, i)}, the
     * CPU figure against the heap ceiling. The first is the 32-bit cap of four
     * that feeds {@code k}, and the third is in the out-of-memory fallback;
     * both are left alone.
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/client/renderer/WorldRenderer;"
                    + "Ljava/util/concurrent/Executor;ZLnet/minecraft/client/renderer/RegionRenderCacheBuilder;I)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I", ordinal = 1))
    private int vulkanmodnext$chooseBuilderCount(int cores, int heapCeiling) {
        return ChunkBuildThreads.builders(cores, heapCeiling);
    }
}
