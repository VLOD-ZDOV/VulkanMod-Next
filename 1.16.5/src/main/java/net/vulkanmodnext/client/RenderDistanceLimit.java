package net.vulkanmodnext.client;

import net.minecraft.client.AbstractOption;
import net.minecraft.client.Minecraft;

/**
 * How far the render-distance slider is allowed to go.
 *
 * Vanilla stops at 16, or 32 on a 64-bit JVM with a heap of a gigabyte or
 * more — {@code GameSettings}' constructor decides it once, before any mod
 * loads, by calling {@code setMaxValue} on the option. The 1.12.2 mod has
 * raised that to 64 since 0.2.0, and {@link #EXTREME} raises it again to 128 for
 * anyone who wants to find out what their machine does with it. The same two
 * numbers here, so a player moving between the builds finds the same slider.
 *
 * <h2>What the extra distance actually costs</h2>
 *
 * Not "more chunks drawn" — more chunks <em>allocated</em>. {@code ViewFrustum}
 * builds a {@code (2d+1) x 16 x (2d+1)} grid of {@code ChunkRender} objects when
 * the distance changes and keeps every one of them alive, whether or not there
 * is terrain in that cell:
 *
 * <pre>
 *   32  ->    65  x 16 x  65 =     67 600 chunks
 *   64  ->   129 x 16 x 129 =    266 256 chunks
 *  128  ->   257 x 16 x 257 =  1 056 784 chunks
 * </pre>
 *
 * Nothing in that grid is capped at 32 — it is one flat array sized from the
 * distance, and the cells are placed by modulo — so the larger grid works; it
 * is only four times the objects and four times the memory for each doubling.
 * And more than objects: every {@code ChunkRender} makes a {@code VertexBuffer}
 * for each of the five chunk layers in its field initialiser, each one a
 * {@code glGenBuffers}, so 64 asks the driver for 1.3 million buffer names and
 * 128 for 5.3 million before a single block is drawn.
 *
 * <h2>Where the world stops</h2>
 *
 * The grid is not the world. The integrated server clamps its own view
 * distance to 33 in {@code ChunkManager.setViewDistance}, exactly as 1.12.2's
 * {@code PlayerChunkMap} clamps to 32, so in single player nothing arrives past
 * 32 chunks however far the slider goes. What the larger number buys is a
 * server, or a mod, that sends more — the same as on 1.12.2.
 *
 * <h2>Why the cap moves rather than disappearing</h2>
 *
 * Lowering the limit has to pull the current value down with it, or turning the
 * switch back off would leave the game running at a distance its own slider can
 * no longer express — the video settings would show the bar pinned at the far
 * end and moving it would jump the value, which reads as a bug rather than as a
 * setting.
 */
public final class RenderDistanceLimit {

    /** What the 1.12.2 mod has allowed since 0.2.0. */
    public static final int NORMAL = 64;
    /** What the switch unlocks. */
    public static final int EXTREME = 128;

    private RenderDistanceLimit() {
    }

    public static int max() {
        return VulkanConfig.on("extremeRenderDistance") ? EXTREME : NORMAL;
    }

    /**
     * Applies the current limit to the game's own slider and pulls the render
     * distance back inside it. Safe to call before a world exists; the options
     * are loaded before any mod is constructed, so they are always there.
     */
    public static void apply() {
        int max = max();
        AbstractOption.RENDER_DISTANCE.setMaxValue(max);
        net.vulkanmodnext.VulkanModNext.LOGGER.info("Render distance slider ends at {}", max);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        if (mc.options.renderDistance > max) {
            // The renderer notices on its next frame — it compares against
            // the distance it last built for and rebuilds the grid — so there
            // is nothing to call here but the save.
            mc.options.renderDistance = max;
            mc.options.save();
        }
    }
}
