package net.vulkanmodnext.client;

import net.minecraft.client.renderer.texture.NativeImage;
import net.vulkanmodnext.vkimpl.VkContext;

/**
 * Carries the block atlas's animation frames over to the Vulkan copy of it.
 *
 * <h2>Why this is needed at all</h2>
 *
 * The renderer draws the world from its own copy of the block atlas, taken
 * once when the atlas is built. The game animates water, lava, fire, portals
 * and the rest by uploading new frames into the OpenGL atlas every tick — into
 * the original, which the copy never sees. Without this, every animated block
 * drawn through Vulkan stands still on its first frame while the same block in
 * the hand, or with the terrain switched off, moves. On 1.12.2 this mirror
 * existed from the start; the port brought over the receiving method and
 * nothing that called it, so water on 1.16.5 had been frozen for as long as
 * the port had drawn it.
 *
 * <h2>How</h2>
 *
 * Every frame a sprite uploads is copied out of the image it is uploaded from,
 * level by level, into one flat buffer. At the end of the atlas's tick the
 * whole batch goes over at once: a tick may move dozens of sprites, and each
 * hand-over costs a queue submission and a wait however few pixels it holds.
 *
 * <p>The images are the game's own {@link NativeImage}s, which store a pixel
 * as ABGR; the renderer takes 0xAARRGGBB, the order 1.12.2 hands it, so red
 * and blue are swapped on the way in.
 */
public final class AtlasAnimations {

    /** Level, x, y, width, height, offset into the pixels; six ints a region. */
    private static final int HEADER_INTS = 6;

    private static int[] header = new int[HEADER_INTS * 64];
    private static int headerCount;
    private static int[] pixels = new int[64 * 1024];
    private static int pixelCount;

    private AtlasAnimations() {
    }

    /**
     * One sprite frame on its way into the block atlas.
     *
     * @param frames the images the frame is taken from, one per mip level
     * @param frameX where in those images the frame starts, at level 0
     * @param atlasX where in the atlas the sprite lives, at level 0
     */
    public static void record(NativeImage[] frames, int frameX, int frameY,
                              int atlasX, int atlasY, int width, int height, int levels) {
        for (int level = 0; level < levels && level < frames.length
                && width >> level > 0 && height >> level > 0; level++) {
            NativeImage image = frames[level];
            if (image == null) {
                break;
            }
            int w = width >> level;
            int h = height >> level;
            int sx = frameX >> level;
            int sy = frameY >> level;
            if (sx + w > image.getWidth() || sy + h > image.getHeight()) {
                // A frame that reaches past its own image is a sprite some mod
                // built by hand. Skipped rather than read out of bounds; the
                // copy keeps its previous frame, which is the lesser wrong.
                break;
            }
            ensureHeader();
            ensurePixels(pixelCount + w * h);
            header[headerCount++] = level;
            header[headerCount++] = atlasX >> level;
            header[headerCount++] = atlasY >> level;
            header[headerCount++] = w;
            header[headerCount++] = h;
            header[headerCount++] = pixelCount;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = image.getPixelRGBA(sx + x, sy + y);
                    pixels[pixelCount++] = (abgr & 0xFF00FF00)
                            | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
                }
            }
        }
    }

    /** Hands this tick's frames over, or drops them when nobody draws with them. */
    public static void flush() {
        if (headerCount == 0) {
            return;
        }
        VkContext context = VulkanStartup.context();
        if (context != null && VulkanConfig.isTerrainEnabled()) {
            context.updateAtlasRegions(header, headerCount, pixels, pixelCount);
        }
        headerCount = 0;
        pixelCount = 0;
    }

    private static void ensureHeader() {
        if (headerCount + HEADER_INTS > header.length) {
            header = java.util.Arrays.copyOf(header, header.length * 2);
        }
    }

    private static void ensurePixels(int needed) {
        if (needed <= pixels.length) {
            return;
        }
        int size = pixels.length;
        while (size < needed) {
            size *= 2;
        }
        pixels = java.util.Arrays.copyOf(pixels, size);
    }
}
