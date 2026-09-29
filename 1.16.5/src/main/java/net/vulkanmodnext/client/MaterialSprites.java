package net.vulkanmodnext.client;

import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.vkimpl.VkContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Which patches of the block atlas belong to which material.
 *
 * <h2>Why the translucent layer needs its own answer</h2>
 *
 * Everywhere else a material is recorded per vertex while the chunk is built
 * ({@link MaterialRuns}). That cannot work for the translucent layer, and the
 * reason is not an oversight anywhere: the game sorts that layer's quads by
 * distance after building it, and sorts them again — without rebuilding — every
 * time the camera moves far enough, so that water draws back to front. A label
 * numbered by vertex describes the wrong surface the moment the quads move.
 * Marked only when a chunk's translucent layer was a single material, water
 * went grey next to a single block of ice.
 *
 * What survives the sort is what the quad carries with it. Its texture does:
 * the sort moves whole quads, and a quad's texture coordinates move with it.
 * So for this one layer the material is read off the atlas instead — the water
 * texture is water wherever it ends up in the buffer, and no reordering can
 * separate the two.
 *
 * <h2>What that costs</h2>
 *
 * A texture is not a material, and this is honest about being a lookup of
 * specific textures rather than a general answer. A modded block with a texture
 * of its own is not recognised; a block borrowing the water texture counts as
 * water, which for anything that makes water look like water is the answer you
 * wanted anyway. It is used only where the per-vertex labels cannot be, and the
 * two are never asked at once.
 */
public final class MaterialSprites {

    private static final Logger LOGGER = LogManager.getLogger("VulkanModNext");

    /** Sprite name and the material it stands for; the order is the search order. */
    private static final String[] NAMES = {
            "minecraft:block/water_still",
            "minecraft:block/water_flow",
            // Water has a third texture, and forgetting it showed: the game
            // draws the face where water meets a block with a solid face —
            // glass, most visibly — with this one rather than with the flowing
            // texture (FluidBlockRenderer on 1.12.2, and the same here). Water against a glass wall was
            // grey exactly along the contact and blue everywhere else.
            "minecraft:block/water_overlay",
            "minecraft:block/ice",
            "minecraft:block/lava_still",
            "minecraft:block/lava_flow",
    };
    private static final int[] MATERIALS = {
            MaterialRuns.WATER,
            MaterialRuns.WATER,
            MaterialRuns.WATER,
            MaterialRuns.ICE,
            MaterialRuns.LAVA,
            MaterialRuns.LAVA,
    };

    private MaterialSprites() {
    }

    /**
     * Looks the sprites up in the freshly stitched atlas and sends their
     * rectangles over.
     *
     * Called after every atlas upload, because stitching decides afresh where
     * each sprite lands and a rectangle from the last resource pack would point
     * at whatever is there now.
     */
    public static void handOver(VkContext context, AtlasTexture atlas) {
        int[] materials = new int[NAMES.length];
        float[] rects = new float[NAMES.length * 4];
        int found = 0;
        for (int i = 0; i < NAMES.length; i++) {
            TextureAtlasSprite sprite;
            try {
                sprite = atlas.getSprite(new ResourceLocation(NAMES[i]));
            } catch (Throwable t) {
                continue;
            }
            if (sprite == null || !NAMES[i].equals(sprite.getName().toString())
                    || sprite.getU0() == sprite.getU1()) {
                // Missing sprites come back as the "missing texture" one, which
                // every unknown name shares. Sending it would paint everything
                // that failed to load as water. It is a real rectangle in the
                // atlas, so only its name gives it away.
                continue;
            }
            materials[found] = MATERIALS[i];
            rects[found * 4] = sprite.getU0();
            rects[found * 4 + 1] = sprite.getV0();
            rects[found * 4 + 2] = sprite.getU1();
            rects[found * 4 + 3] = sprite.getV1();
            found++;
        }
        context.setMaterialSprites(materials, rects, found);
        LOGGER.info("Handed {} material sprites to Vulkan", found);
    }
}
