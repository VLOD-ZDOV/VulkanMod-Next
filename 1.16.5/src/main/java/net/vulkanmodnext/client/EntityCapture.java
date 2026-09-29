package net.vulkanmodnext.client;

import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.minecraft.client.renderer.texture.Texture;
import net.vulkanmodnext.mixin.ModelPartAccess;
import net.vulkanmodnext.mixin.RenderStateAccess;
import org.lwjgl.opengl.GL11C;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;

/**
 * Reads what the game draws for every creature part, and draws nothing.
 *
 * <h2>What it is for</h2>
 *
 * On 1.12.2 this was the experiment that decided whether creatures could be
 * taken into Vulkan at all: how many parts a frame, how many skins, what
 * placing them costs, and whether the placement composed by hand agreed with
 * the driver's. The answer was yes, and {@link EntityGeometry} is what came of
 * it. It stays as an instrument, because "how much of this scene could Vulkan
 * draw, and what is it made of" is a question worth asking of any world.
 *
 * <h2>What it measures here, and what it no longer has to</h2>
 *
 * The placement check is gone, and not by omission: on this version the pose
 * of every part is the game's own matrix stack, on the processor, and the
 * drawing path replays the game's own {@code render} rather than composing
 * anything. There is no second arithmetic to verify against the first.
 *
 * <p>What is left is the census, per frame: parts, quads, distinct skins and
 * what copying them would cost — and a number this version needs and 1.12.2
 * did not, how many parts arrive with a builder {@code EntityGeometry} can
 * name the render type of. Anything else (the glint on armour, a glowing
 * outline, sprite-sheet models) is left to the game, and this says how much
 * of the scene that is.
 *
 * <h2>The cost is a difference</h2>
 *
 * Armed on every other frame, and the whole entity pass timed both ways. A
 * clock around each part would cost a share of the answer large enough to
 * change it, and a clock around the pass alone measures the game's entity
 * rendering, of which this is a small part — the first 1.12.2 version reported
 * that as its own overhead and made a hook doing almost nothing look like it
 * cost a millisecond.
 */
public final class EntityCapture {

    private static boolean everyOther;
    private static boolean armed;
    private static boolean measuring;
    private static long passStartedNanos;
    private static long armedNanos;
    private static long armedFrames;
    private static long bareNanos;
    private static long bareFrames;

    private static int frameParts;
    private static int frameQuads;
    private static int frameNamed;
    private static int frameUnnamed;
    private static int lastParts;
    private static int lastQuads;
    private static int lastNamed;
    private static int lastUnnamed;
    private static long partsSeen;

    /**
     * Which skins the scene needs, and what copying them all would cost — the
     * number that decides whether the skin slots are a list or a cache.
     * Looked up once per change of builder rather than once per bone: a
     * creature's parts share one.
     */
    private static final HashMap<Integer, int[]> TEXTURE_SIZES = new HashMap<Integer, int[]>();
    private static final HashSet<Integer> FRAME_TEXTURES = new HashSet<Integer>();
    /** Render type names seen with a builder we can name, and how often; the census's detail. */
    private static final HashMap<String, Integer> KINDS_SEEN = new HashMap<String, Integer>();
    private static final IdentityHashMap<RenderType, String> NAMES =
            new IdentityHashMap<RenderType, String>();
    private static IVertexBuilder lastBuilder;
    private static long texturePixels;
    private static int lastFrameTextures;

    private EntityCapture() {
    }

    public static boolean armed() {
        return armed;
    }

    /** Called where the entity pass opens; see {@code EntityPassMixin}. */
    public static void arm() {
        everyOther = !everyOther;
        measuring = VulkanConfig.on("entityCapture");
        armed = measuring && everyOther;
        passStartedNanos = System.nanoTime();
    }

    /**
     * Keeps the last pass that saw anything, rather than the last pass: a
     * frame whose pass was empty would otherwise report the hook as dead.
     */
    public static void disarm() {
        if (!measuring) {
            return;
        }
        measuring = false;
        long elapsed = System.nanoTime() - passStartedNanos;
        if (armed) {
            armedNanos += elapsed;
            armedFrames++;
            if (frameParts > 0) {
                lastParts = frameParts;
                lastQuads = frameQuads;
                lastNamed = frameNamed;
                lastUnnamed = frameUnnamed;
                lastFrameTextures = FRAME_TEXTURES.size();
            }
        } else {
            bareNanos += elapsed;
            bareFrames++;
        }
        armed = false;
        frameParts = 0;
        frameQuads = 0;
        frameNamed = 0;
        frameUnnamed = 0;
        lastBuilder = null;
        FRAME_TEXTURES.clear();
    }

    /**
     * One part the game is about to draw itself.
     *
     * Called only for parts {@link EntityGeometry} did not take, and never for
     * the calls a taken part makes into our own builder — so with both on, this
     * counts what is still the game's. Nothing is allowed out of here: a throw
     * would take down the entity pass of a game drawing perfectly well.
     */
    public static void observe(ModelRenderer part, IVertexBuilder builder) {
        if (!armed || part == null || !part.visible || EntityGeometry.isOurs(builder)) {
            return;
        }
        try {
            int boxes = ((ModelPartAccess) part).vulkanmodnext$cubes().size();
            partsSeen++;
            if (boxes == 0) {
                return;
            }
            frameParts++;
            // Six faces a box, which is what the game's box always builds.
            frameQuads += boxes * 6;
            RenderType type = EntityGeometry.typeOf(builder);
            if (type == null) {
                frameUnnamed++;
                return;
            }
            frameNamed++;
            if (builder != lastBuilder) {
                lastBuilder = builder;
                noteType(type);
            }
        } catch (Throwable ignored) {
            // As above.
        }
    }

    private static void noteType(RenderType type) {
        String name = NAMES.get(type);
        if (name == null) {
            name = ((RenderStateAccess) type).vulkanmodnext$name();
            NAMES.put(type, name);
        }
        Integer seen = KINDS_SEEN.get(name);
        KINDS_SEEN.put(name, seen == null ? 1 : seen + 1);
        int texture = textureOf(type);
        if (texture <= 0 || !FRAME_TEXTURES.add(texture) || TEXTURE_SIZES.containsKey(texture)) {
            return;
        }
        // Asked of the driver exactly once per texture per session — a couple
        // of hundred calls in a long session, nowhere near the hot path.
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        int w = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
        int h = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        if (w <= 0 || h <= 0) {
            TEXTURE_SIZES.put(texture, new int[]{0, 0});
            return;
        }
        TEXTURE_SIZES.put(texture, new int[]{w, h});
        texturePixels += (long) w * h;
    }

    /** The render type's texture by OpenGL name, 0 for none or not loaded. */
    private static int textureOf(RenderType type) {
        net.minecraft.util.ResourceLocation location = EntityGeometry.textureLocation(type);
        if (location == null) {
            return 0;
        }
        Texture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        return texture == null ? 0 : texture.getId();
    }

    /** One line for the diagnostics report; this is the whole product. */
    public static String stats() {
        if (!VulkanConfig.on("entityCapture")) {
            return "entity capture: off";
        }
        double withCapture = armedFrames == 0 ? 0.0 : armedNanos / (double) armedFrames;
        double without = bareFrames == 0 ? 0.0 : bareNanos / (double) bareFrames;
        double overhead = withCapture - without;
        return "entity capture: " + lastParts + " parts, " + lastQuads + " quads a frame left to "
                + "the game (" + lastNamed + " with a builder we can name, " + lastUnnamed
                + " wrapped); entity pass " + String.format("%.3f", withCapture / 1e6)
                + " ms with, " + String.format("%.3f", without / 1e6) + " ms without, so "
                + String.format("%.3f", overhead / 1e6) + " ms is ours; " + partsSeen
                + " parts over the session; render types " + KINDS_SEEN
                + String.format("; skins %d distinct (%.1f MiB as RGBA), %d in the busiest frame",
                TEXTURE_SIZES.size(), texturePixels * 4.0 / (1024.0 * 1024.0),
                lastFrameTextures);
    }
}
