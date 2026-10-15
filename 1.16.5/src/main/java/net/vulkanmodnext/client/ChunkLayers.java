package net.vulkanmodnext.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.vertex.VertexBuffer;


/**
 * Which layer a chunk buffer belongs to.
 *
 * <p>Kept as a field on the buffer itself (see VertexBufferMixin). It used to
 * be a synchronised weak map on the belief that it was written once per
 * buffer; it is written on every getBuffer, which the terrain walk calls for
 * every visible chunk and layer each frame.
 */
public final class ChunkLayers {

    private static RenderType layerOf(VertexBuffer buffer) {
        return buffer == null ? null : ((VertexBufferSlot) (Object) buffer).vulkanmodnext$layer();
    }

    private ChunkLayers() {
    }

    public static void note(VertexBuffer buffer, RenderType layer) {
        // Asked on every getBuffer — every visible chunk, every layer, every
        // frame — so this is a field on the buffer, written once, rather than
        // the synchronised map it used to be.
        if (buffer != null && layerOf(buffer) == null) {
            ((VertexBufferSlot) (Object) buffer).vulkanmodnext$layer(layer);
        }
    }

    /**
     * @return true when this buffer holds the translucent layer. Unknown reads
     *         as false, which is the safe direction: an unsorted chunk draws
     *         whole, where sorting one that should not be is wasted work on
     *         every rebuild.
     */
    public static boolean isTranslucent(VertexBuffer buffer) {
        return layerOf(buffer) == RenderType.translucent();
    }

    /**
     * Whether Vulkan draws this buffer's layer at all.
     *
     * Every chunk layer but tripwire. Tripwire is drawn after translucent and
     * stays with the game (see TerrainFrame.ordinalOf), so its geometry is
     * neither copied nor taken out of the game's buffer: emptying it with the
     * other layers made every tripwire in the world invisible, drawn by
     * neither renderer.
     */
    public static boolean isDrawnByVulkan(VertexBuffer buffer) {
        RenderType layer = layerOf(buffer);
        return layer != null && layer != RenderType.tripwire();
    }

    /**
     * Whether this buffer belongs to a chunk at all.
     *
     * <h2>Why the mirror has to ask</h2>
     *
     * {@code VertexBuffer} is not only used for chunks. The sky, the stars and
     * the clouds are drawn from ones of their own, with vertex formats of their
     * own — the sky is three floats a vertex where a chunk is thirty-two bytes.
     * Mirroring those means reading a position out of what is somebody else's
     * texture coordinate.
     *
     * <p>It showed up as a number rather than a crash: the packer counted
     * thirty-seven thousand positions, thirty-four thousand light values and
     * forty-one thousand texture coordinates outside the range they are
     * supposed to occupy, and the picture lost half its colours. Nothing threw.
     *
     * <p>A chunk buffer is one the game asked for by layer, which is exactly
     * what this field records — so the answer is already here.
     */
    public static boolean isChunkBuffer(VertexBuffer buffer) {
        return layerOf(buffer) != null;
    }
}
