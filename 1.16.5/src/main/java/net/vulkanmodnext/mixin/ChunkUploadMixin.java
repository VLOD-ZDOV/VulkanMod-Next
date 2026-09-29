package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.vulkanmodnext.client.ChunkGeometry;
import net.vulkanmodnext.client.ChunkLayers;
import net.vulkanmodnext.client.ChunkMirror;
import net.vulkanmodnext.client.MaterialRuns;
import net.vulkanmodnext.client.VulkanConfig;
import net.vulkanmodnext.client.VertexBufferSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Sees a chunk's geometry on the thread that built it.
 *
 * <p>The 1.12.2 mod hooks {@code uploadChunk} for the same reason, and the
 * reason survives the version change: this is called from a builder thread, and
 * what happens after it is a queued OpenGL upload that the game gives a hard
 * budget of a quarter of the frame. Copying the geometry needs no driver call
 * at all, so doing it here spends a builder thread instead of that budget.
 *
 * <p>One thing did change, and it is not cosmetic: on 1.12.2 the hook is handed
 * the layer it is uploading. Here it is handed a {@code VertexBuffer} and
 * nothing else, so which layer this is has to be learned elsewhere — see
 * {@code ChunkBufferLayerMixin}.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class ChunkUploadMixin {

    @Inject(method = "uploadChunkLayer", at = @At("HEAD"))
    private void vulkanmodnext$see(BufferBuilder builder, VertexBuffer target,
                                   CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (target == null) {
            return;
        }
        int slot = ((VertexBufferSlot) (Object) target).vulkanmodnext$slotOrAssign();
        // Before the geometry, and on this thread: this is the last place that
        // knows which layer the slot is for, and the copy needs to know before
        // it decides whether it may sort the quads.
        boolean translucent = ChunkLayers.isTranslucent(target);
        ChunkMirror.onLayer(slot, translucent);
        if (VulkanConfig.on("materialTags")) {
            // Also before the geometry: this is the last place that still knows
            // which builder held the chunk, and the runs are keyed by nothing
            // else.
            MaterialRuns.publish(slot, builder, translucent);
        }
        ChunkGeometry.offer(slot, builder);
    }
}
