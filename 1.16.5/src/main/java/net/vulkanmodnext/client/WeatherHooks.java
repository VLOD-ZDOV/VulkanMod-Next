package net.vulkanmodnext.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.mixin.WorldRendererAccess;
import net.vulkanmodnext.vkimpl.VkContext;

/**
 * Rain and snow, built by the game at the translucent layer and handed to
 * Vulkan.
 *
 * Like the 1.12.2 build this does not replace the loop. {@code
 * renderSnowAndRain} is a hundred and fifty lines of column walking, biome
 * lookups, seeded randomness and Forge's hook for a dimension's own weather,
 * and reproducing it would mean owning all of that. What is replaced is two
 * calls inside it: which sheet is bound, and the draw.
 *
 * <p>What is new is <em>when</em> it runs. The game calls it after the clouds,
 * long after the translucent pass has been submitted, so the method is called
 * once more, early, from the translucent layer — see {@link Sprites} — and the
 * game's own call later in the frame is skipped. Everything the method reads
 * is fixed for the frame: the tick counter, the partial tick, the camera, and
 * a random generator seeded afresh from each column's coordinates. The early
 * call builds exactly the quads the late one would have.
 *
 * <p>The quads arrive relative to the camera, which is the origin the
 * terrain's matrix is built around, so they need no adjustment.
 */
public final class WeatherHooks {

    /** Which sheet the game bound last, as a sprite slot. */
    private static int slot = Sprites.SLOT_RAIN;

    /** True only while the early call is running. */
    private static boolean capturing;
    /** The early call ran this frame and the game's own must not draw again. */
    private static boolean taken;
    /** Batches handed over during this frame's early call. */
    private static int parkedThisFrame;

    /**
     * What the weather pass actually handed over, counted where it happens.
     *
     * The 1.12.2 build learned to keep these after rain went missing with the
     * sky darkening correctly: the two stories — the game built no quads, or it
     * built them and this path lost them — need opposite fixes, and nothing
     * outside this class can tell them apart.
     */
    private static long batches;
    private static long vertices;
    private static long toVulkan;
    private static long drawnByGame;
    private static long framesTaken;
    private static long framesDeclined;

    private WeatherHooks() {
    }

    static void beginFrame() {
        taken = false;
        parkedThisFrame = 0;
    }

    /**
     * Runs the game's weather now, into Vulkan.
     *
     * @return how many batches were parked; zero when nothing was run
     */
    static int capture(VkContext context, WorldRenderer renderer, RenderType layer,
                       MatrixStack matrices, double viewX, double viewY, double viewZ) {
        if (!Sprites.usable(context, VulkanConfig.on("vulkanWeather"))) {
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 0;
        }
        // A dimension that draws its own weather is left entirely alone: none
        // of the calls this class redirects would run, so there would be
        // nothing to take and the late call would have been skipped for
        // nothing.
        if (mc.level.effects().getWeatherRenderHandler() != null) {
            return 0;
        }
        if (mc.level.getRainLevel(mc.getFrameTime()) <= 0.0f) {
            // Nothing to draw. Not marked taken: the game's own call returns
            // at the same test, and skipping it would change nothing.
            return 0;
        }
        parkedThisFrame = 0;
        capturing = true;
        // The game's model-view at its own call, so that anything this sends
        // back to OpenGL is drawn where it would have been.
        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(matrices.last().pose());
        try {
            ((WorldRendererAccess) renderer).vulkanmodnext$renderSnowAndRain(
                    mc.gameRenderer.lightTexture(), mc.getFrameTime(), viewX, viewY, viewZ);
            taken = true;
        } catch (Throwable t) {
            Sprites.fail("Drawing weather", t);
            BufferBuilder builder = Tessellator.getInstance().getBuilder();
            if (builder.building()) {
                builder.end();
            }
            builder.discard();
            // Not taken: the game's own call runs, and meets whatever threw
            // in its own words.
            taken = false;
        } finally {
            capturing = false;
            RenderSystem.popMatrix();
            // The weather leaves blending off, culling on, depth writes off,
            // the light layer off and the rain sheet bound. All of that is the
            // translucent layer's state until this hook returns, and if the
            // Vulkan pass then declines, the game draws its water under it.
            // Setting the layer up again restores it through the game's own
            // state cache, which a raw push and pop of OpenGL state would not.
            layer.setupRenderState();
        }
        return taken ? parkedThisFrame : 0;
    }

    /** See {@link ParticleHooks#settle}. */
    static void settle(boolean drawn) {
        // Nothing parked means every batch was refused and drawn by the game
        // during the capture: the weather is on the screen whatever the pass
        // did, and letting the late call run would draw it twice.
        if (!taken || parkedThisFrame == 0) {
            return;
        }
        if (drawn) {
            framesTaken++;
            return;
        }
        framesDeclined++;
        // The quads went with the pass. The game draws the weather itself at
        // its own moment; any batch sent to OpenGL during the capture is drawn
        // twice in this one frame, which is the cheap end of this.
        taken = false;
    }

    /** @return true when the game's own weather call must not draw this frame */
    public static boolean skipGameCall() {
        return taken && !capturing;
    }

    public static void noteTexture(ResourceLocation location) {
        if (Sprites.SNOW.equals(location)) {
            slot = Sprites.SLOT_SNOW;
        } else if (Sprites.RAIN.equals(location)) {
            slot = Sprites.SLOT_RAIN;
        }
    }

    /**
     * The weather's own {@code tessellator.end()}.
     *
     * Outside the early call this is the game's draw, untouched. Inside it the
     * batch goes to Vulkan, and if the renderer will not take it, it is drawn
     * by the game right here with the state the weather code has just set —
     * earlier in the frame than usual, but drawn: a rain field is one batch,
     * and a frame that dropped a refused one had no rain in it at all.
     */
    public static void end(Tessellator tessellator) {
        if (!capturing) {
            tessellator.end();
            return;
        }
        BufferBuilder builder = tessellator.getBuilder();
        builder.end();
        batches++;
        VkContext context = VulkanStartup.context();
        int took = context == null ? -1
                : Sprites.submitFinished(context, builder, slot, Sprites.DEFAULT_CUTOFF);
        if (took >= 0) {
            vertices += took;
            toVulkan++;
            parkedThisFrame++;
        } else {
            drawnByGame++;
            WorldVertexBufferUploader.end(builder);
        }
    }

    /** Counted since the session began; see {@link ParticleHooks#stats}. */
    public static String stats() {
        if (!VulkanConfig.on("vulkanWeather")) {
            return "weather: drawn by the game (Vulkan weather is off)";
        }
        return String.format("weather: %d frames through Vulkan, %d declined back to the game, "
                        + "%d batches built, %d of them taken by Vulkan (%d vertices), "
                        + "%d drawn by the game after a refusal%s",
                framesTaken, framesDeclined, batches, toVulkan, vertices, drawnByGame,
                Sprites.isBroken() ? " — path disabled after a failure" : "");
    }
}
