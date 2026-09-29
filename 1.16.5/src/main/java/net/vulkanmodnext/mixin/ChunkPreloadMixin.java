package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.vulkanmodnext.client.CpuSavings;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Lets chunks outside the view be rebuilt, which vanilla never does.
 *
 * {@code setupRender} flood-fills outward from the camera's section, and a
 * neighbour enters the visible list only if {@code ClippingHelper.isVisible}
 * accepts it. The loop that queues rebuilds then walks only that list, so a
 * section needing a rebuild that is not on screen is never even considered,
 * however idle the builder threads are. Turn the camera and it is discovered
 * then, from scratch. 1.16.5 is the same as 1.12.2 in this, line for line.
 *
 * At normal render distances nobody notices. At long ones the number of
 * sections wanting a build dwarfs what fits in a frame, so the world visibly
 * fills in along whatever you are looking at.
 *
 * This tops the build queue up from the full section grid once the visible
 * work is dealt with. Two rules keep it from making things worse:
 *
 * - it only adds when the queue has run dry, not merely when it is short, so
 *   on-screen sections always win the builder threads;
 * - it scans a bounded slice of the grid per frame, resuming where it left
 *   off, because the grid is a quarter of a million cells at distance 64 and
 *   sweeping it every frame would trade slow loading for a stutter.
 *
 * Only the build queue is touched. What gets drawn is still decided by the
 * visible list, so this cannot put off-screen geometry on screen.
 *
 * <h2>One test 1.12.2 did not need</h2>
 *
 * The visible list only ever holds sections whose four horizontal neighbours
 * are loaded, and a build of one that is missing a neighbour is cancelled on
 * the builder thread and marks the section dirty again. Queued without that
 * test, every section at the edge of the loaded world would be handed to a
 * builder, thrown back, and found again on the next sweep, forever.
 */
@Mixin(WorldRenderer.class)
public abstract class ChunkPreloadMixin {

    @Shadow
    private ViewFrustum viewArea;

    @Shadow
    private Set<ChunkRenderDispatcher.ChunkRender> chunksToCompile;

    /** Resume point, so successive frames sweep the whole grid. */
    @Unique
    private int vulkanmodnext$scanCursor;

    @Inject(method = "setupRender", at = @At("RETURN"))
    private void vulkanmodnext$preloadOffscreenChunks(ActiveRenderInfo camera, ClippingHelper frustum,
                                                     boolean hasCapturedFrustum, int frame,
                                                     boolean spectator, CallbackInfo ci) {
        if (!VulkanConfig.on("chunkPreload") || viewArea == null) {
            return;
        }
        ChunkRenderDispatcher.ChunkRender[] grid = viewArea.chunks;
        if (grid == null || grid.length == 0) {
            return;
        }
        // Only when the queue has run dry, not merely when it is short. Topping
        // it up while work remains would keep the builders busy every frame
        // forever instead of only while there is catching up to do — and a
        // queue that is not empty also makes the game redo its visibility
        // search on the next frame, so a queue kept topped up is a search paid
        // for on every frame.
        if (!chunksToCompile.isEmpty()) {
            return;
        }
        int room = VulkanConfig.get("preloadQueue");
        int budget = VulkanConfig.get("preloadScan");
        int cursor = vulkanmodnext$scanCursor;
        if (cursor >= grid.length) {
            cursor = 0;
        }
        int scanned = 0;
        while (scanned < budget && room > 0) {
            ChunkRenderDispatcher.ChunkRender chunk = grid[cursor];
            cursor++;
            scanned++;
            if (cursor >= grid.length) {
                cursor = 0;
            }
            if (chunk != null && chunk.isDirty() && chunk.hasAllNeighbors()
                    && chunksToCompile.add(chunk)) {
                room--;
                CpuSavings.countPreloaded();
            }
        }
        vulkanmodnext$scanCursor = cursor;
    }
}
