package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.Texture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.mixin.BufferBuilderAccess;
import net.vulkanmodnext.vkimpl.VkContext;

import java.nio.ByteBuffer;

/**
 * Particles and weather as camera-facing quads, parked for the translucent
 * Vulkan pass.
 *
 * <h2>Why the capture happens early</h2>
 *
 * On 1.12.2 the game draws particles and weather <em>before</em> the water, so
 * whatever they hand over is simply waiting when the translucent pass is
 * recorded. On this version the order is the other way round: translucent,
 * tripwire, particles, clouds, weather. Handing sprites over at the game's own
 * moment would put them into a pass that was submitted a millisecond earlier,
 * where the renderer throws them away at the top of the next frame as stale.
 *
 * <p>So the geometry is built at the translucent layer instead — by the game's
 * own code, with the same camera and the same partial tick it would use a
 * moment later — and the game's later draws are skipped for whatever was
 * taken. The order this puts them in is 1.12.2's, and on this version it is an
 * improvement rather than a compromise: vanilla 1.16.5 draws particles after
 * water that has written depth, so a particle behind a water surface or a pane
 * of glass is not drawn at all outside Fabulous graphics. Here it is under the
 * water's colour, as it is on 1.12.2.
 *
 * <p>What changes the other way: clouds are drawn by the game after our
 * composite, so they now land over rain rather than under it. Rain in front of
 * a cloud reads a little thinner against it.
 *
 * <h2>The rule</h2>
 *
 * Nothing taken is ever lost. A batch the renderer refuses is not marked
 * taken, and the game draws it at its own moment; a translucent pass that
 * declines un-marks everything, and the game draws all of it.
 */
public final class Sprites {

    /** The renderer's slots: 0 is the terrain's own block atlas, shared. */
    static final int SLOT_BLOCK_ATLAS = 0;
    static final int SLOT_PARTICLES = 1;
    static final int SLOT_RAIN = 2;
    static final int SLOT_SNOW = 3;

    static final ResourceLocation RAIN = new ResourceLocation("textures/environment/rain.png");
    static final ResourceLocation SNOW = new ResourceLocation("textures/environment/snow.png");

    /** Vanilla's two alpha tests: the default one, and the translucent sheet's. */
    static final float DEFAULT_CUTOFF = 0.1f;
    static final float TRANSLUCENT_CUTOFF = 0.003921569f;

    private static final int PARTICLE_STRIDE = DefaultVertexFormats.PARTICLE.getVertexSize();

    private static boolean sheetsReady;
    private static boolean broken;

    private Sprites() {
    }

    /**
     * Whether this frame's sprites may go to Vulkan, asked at the translucent
     * layer.
     *
     * Fabulous graphics is declined outright. There the game draws water,
     * particles and weather into three separate targets and composes them with
     * a shader of its own; pulling two of the three into our pass would feed
     * that shader targets with holes in them.
     */
    static boolean usable(VkContext context, boolean enabledInSettings) {
        return enabledInSettings && !broken && sheetsReady && context != null
                && !Minecraft.useShaderTransparency() && context.drawsSprites();
    }

    /**
     * Makes sure the particle, rain and snow sheets are in Vulkan.
     *
     * Called on the first layer of the frame, before anything has been
     * submitted, and not on demand at the translucent layer: copying a sheet
     * waits for the device to go idle, and a third of the way through a frame
     * that is a stall for the work already queued. The call is a comparison
     * when the renderer already holds the same OpenGL name, so it is cheap on
     * every frame — and it has to be every frame, because a new block atlas
     * makes the renderer forget every sheet, and a resource reload hands out
     * new names.
     */
    static void syncSheets(VkContext context) {
        sheetsReady = false;
        if (broken || context == null
                || !(VulkanConfig.on("vulkanParticles") || VulkanConfig.on("vulkanWeather"))
                || !context.drawsSprites()) {
            return;
        }
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        try {
            boolean bound = false;
            int[] ids = new int[3];
            ResourceLocation[] sheets = {AtlasTexture.LOCATION_PARTICLES, RAIN, SNOW};
            for (int i = 0; i < 3; i++) {
                Texture texture = textures.getTexture(sheets[i]);
                if (texture == null) {
                    // Binding is what loads it. Rain and snow are never touched
                    // in a world with clear weather, so without this there is
                    // no texture at all to copy.
                    textures.bind(sheets[i]);
                    bound = true;
                    texture = textures.getTexture(sheets[i]);
                }
                ids[i] = texture == null ? 0 : texture.getId();
            }
            if (bound) {
                // Through the texture manager and not through OpenGL: the game
                // caches what it believes is bound, and a raw rebind would leave
                // it believing the rain sheet is — then skip binding it later.
                textures.bind(PlayerContainer.BLOCK_ATLAS);
            }
            if (ids[0] <= 0 || ids[1] <= 0 || ids[2] <= 0) {
                return;
            }
            context.updateSpriteTexture(SLOT_PARTICLES, ids[0]);
            context.updateSpriteTexture(SLOT_RAIN, ids[1]);
            context.updateSpriteTexture(SLOT_SNOW, ids[2]);
            sheetsReady = true;
        } catch (Throwable t) {
            fail("Copying the particle and weather sheets", t);
        }
    }

    /**
     * Hands the batch the builder has just finished to Vulkan, and consumes it
     * only if Vulkan took it.
     *
     * Read in place first, then popped: {@code popNextBuffer} is destructive,
     * and a batch it has handed out can no longer be given back to the game's
     * own draw. So the bytes are read where the pop would read them, and the
     * pop happens only once the renderer has said yes. On a no the builder is
     * exactly as the game left it, and the caller's fallback is the game's own
     * {@code WorldVertexBufferUploader.end}.
     *
     * @return the vertices Vulkan took, the builder then emptied; or -1 when it
     *         did not, the builder then untouched
     */
    static int submitFinished(VkContext context, BufferBuilder builder, int slot, float cutoff) {
        BufferBuilderAccess access = (BufferBuilderAccess) builder;
        java.util.List<BufferBuilder.DrawState> states = access.vulkanmodnext$drawStates();
        int next = access.vulkanmodnext$nextDrawState();
        if (next >= states.size()) {
            return -1;
        }
        BufferBuilder.DrawState state = states.get(next);
        // Only what the renderer was written against: quads, in the game's
        // 28-byte particle layout. Anything else is some other code's batch.
        if (state.mode() != 7 || state.format().getVertexSize() != PARTICLE_STRIDE) {
            return -1;
        }
        int vertices = state.vertexCount();
        int start = access.vulkanmodnext$uploadedBytes();
        ByteBuffer view = access.vulkanmodnext$buffer().duplicate();
        view.limit(start + vertices * PARTICLE_STRIDE);
        view.position(start);
        if (vertices > 0 && !context.submitSprites(view, vertices, slot, cutoff)) {
            return -1;
        }
        builder.popNextBuffer();
        return vertices;
    }

    /** The renderer has just dropped its copies; see TerrainFrame.handOverTextures. */
    static void forgetSheets() {
        sheetsReady = false;
    }

    /** Marks the whole path dead after a failure; the game keeps its own. */
    static void fail(String what, Throwable t) {
        if (broken) {
            return;
        }
        broken = true;
        VulkanModNext.LOGGER.error("{} through Vulkan failed — particles and weather go back "
                + "to the game for the rest of this session", what, t);
    }

    static boolean isBroken() {
        return broken;
    }
}
