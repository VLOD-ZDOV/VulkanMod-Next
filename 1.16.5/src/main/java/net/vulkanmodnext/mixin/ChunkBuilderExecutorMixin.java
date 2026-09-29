package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.Util;
import net.vulkanmodnext.client.ChunkBuildThreads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Executor;

/**
 * Gives the chunk dispatcher its own pool when a thread count is set, instead of
 * the game's shared background pool of at most seven. {@code allChanged} is the
 * only place a dispatcher is made, and this is the one executor it asks for.
 * See {@link ChunkBuildThreads}.
 */
@Mixin(WorldRenderer.class)
public abstract class ChunkBuilderExecutorMixin {

    @Redirect(method = "allChanged",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/Util;backgroundExecutor()Ljava/util/concurrent/Executor;"))
    private Executor vulkanmodnext$chunkBuildExecutor() {
        return ChunkBuildThreads.executor(Util.backgroundExecutor());
    }
}
