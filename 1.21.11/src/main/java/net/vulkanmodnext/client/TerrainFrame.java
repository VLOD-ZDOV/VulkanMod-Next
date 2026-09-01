package net.vulkanmodnext.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.mixin.LevelRendererAccess;
import net.vulkanmodnext.vkimpl.VkContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;

/**
 * Gathers everything the Vulkan terrain renderer needs, and hands it a group of
 * layers at the moment the game was about to draw them itself.
 *
 * <h2>What changed from 1.16.5, and it is most of the shape of this class</h2>
 *
 * <ul>
 * <li><b>Layers come in groups.</b> The game no longer draws one layer at a
 *     time: it builds every draw for every layer once and then issues them in
 *     three groups — opaque, translucent, tripwire — each into a render target
 *     of its own. The renderer underneath still thinks in single layers with a
 *     frame opened by the first and closed by the third, so this class does the
 *     splitting.</li>
 * <li><b>There are four layers, not five.</b> Cutout is no longer split by
 *     mipmapping: one sampler is bound for the whole group, and it is the
 *     mipped one. So the game's CUTOUT is this renderer's <em>cutout
 *     mipped</em>, and its plain cutout slot is what closes the frame with
 *     nothing in it.</li>
 * <li><b>The matrices are handed over, not read back.</b> Both of them are
 *     parameters of {@code renderLevel}, which is also where the fog colour
 *     is. On this version there is no fixed-function state left to read even if
 *     we wanted to.</li>
 * </ul>
 *
 * <h2>The failure mode is "no gain", never "no world"</h2>
 *
 * Every path that is not a plain success — the setting off, the renderer not
 * up, no sections, or anything thrown — leaves the group to the game.
 */
public final class TerrainFrame {

    private static final int MAX_SECTIONS = 1 << 16;

    /** The renderer's own numbering; see {@link #ordinalOf}. */
    private static final int LAYER_SOLID = 0;
    private static final int LAYER_CUTOUT_MIPPED = 1;
    private static final int LAYER_CUTOUT = 2;
    private static final int LAYER_TRANSLUCENT = 3;

    /** Four ints a section: slot, then the world position of its corner. */
    private static int[] slots = new int[4096 * 4];
    private static final float[] mvp = new float[16];
    private static final Matrix4f product = new Matrix4f();
    private static Matrix4f frameProjection;
    private static Matrix4f frameModelView;
    private static final float[] sun = new float[3];
    private static final float[] fog = new float[7];
    private static final float[] cameraOffset = new float[3];
    private static double viewX;
    private static double viewY;
    private static double viewZ;
    private static final int[] taken = new int[4];
    private static final int[] refused = new int[4];
    private static int sentAtlas = -1;
    private static int sentLightmap = -1;
    private static int frames;
    /** Late enough that startup is over: a first frame is not a frame. */
    private static final int ANNOUNCE_ON_FRAME = 60;
    private static boolean announced;

    private TerrainFrame() {
    }

    /**
     * Kept from the top of the world pass; every group of it uses the same
     * matrices.
     *
     * <p>The model-view carries the camera's rotation only — the game applies
     * the position per section — which is the same arrangement as on 1.16.5,
     * and it is why the section origins have to travel beside the slots.
     */
    public static void beginFrame(Matrix4f modelView, Matrix4f projection, Vector4f fogColour) {
        frameModelView = modelView;
        frameProjection = projection;
        frames++;
        if (fogColour != null) {
            fog[0] = fogColour.x();
            fog[1] = fogColour.y();
            fog[2] = fogColour.z();
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        viewX = camera.x;
        viewY = camera.y;
        viewZ = camera.z;
    }

    /**
     * @return true when Vulkan drew this whole group and the game should not
     */
    public static boolean group(ChunkSectionLayerGroup group) {
        VkContext context = VulkanStartup.context();
        if (context == null || frameProjection == null || !VulkanConfig.isTerrainEnabled()) {
            return false;
        }
        try {
            return drawGroup(context, group);
        } catch (Throwable failed) {
            if (!announced) {
                announced = true;
                VulkanModNext.LOGGER.warn("Could not draw a terrain group through Vulkan; the "
                        + "game keeps drawing it", failed);
            }
            // Never the mod's fault that a frame is missing.
            return false;
        }
    }

    private static boolean drawGroup(VkContext context, ChunkSectionLayerGroup group) {
        if (group == ChunkSectionLayerGroup.TRIPWIRE) {
            // A few strings and hooks, drawn after everything else into a
            // target of their own. The cost of letting the game draw them is
            // nothing; the cost of getting the frame order wrong is a frame the
            // renderer cannot close.
            return false;
        }
        if (!handOverTextures(context)) {
            return false;
        }
        modelViewProjection();
        handOverFrameState(context);

        RenderTarget target = group.outputTarget();
        int previousFramebuffer = RenderTargets.bind(target);
        if (previousFramebuffer < 0) {
            return false;
        }
        try {
            if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                return layer(context, ChunkSectionLayer.TRANSLUCENT, LAYER_TRANSLUCENT, target);
            }
            // The opaque group, in the order the renderer expects: the first
            // call opens the frame, the third submits and composites it. The
            // third has nothing in it on this version, and is made anyway —
            // without it the frame is opened and never closed, and the command
            // buffer grows until the driver stops.
            boolean drawn = layer(context, ChunkSectionLayer.SOLID, LAYER_SOLID, target);
            if (!drawn) {
                return false;
            }
            layer(context, ChunkSectionLayer.CUTOUT, LAYER_CUTOUT_MIPPED, target);
            hand(context, LAYER_CUTOUT, 0, target);
            return true;
        } finally {
            RenderTargets.restore(previousFramebuffer);
        }
    }

    private static boolean layer(VkContext context, ChunkSectionLayer layer, int ordinal,
                                 RenderTarget target) {
        int count = gather(layer);
        announceOnce(layer, count);
        return hand(context, ordinal, count, target);
    }

    private static boolean hand(VkContext context, int ordinal, int count, RenderTarget target) {
        boolean drawn = context.renderTerrainLayer(ordinal, slots, count, mvp,
                viewX, viewY, viewZ, target.width, target.height);
        taken[ordinal] += drawn ? 1 : 0;
        refused[ordinal] += drawn ? 0 : 1;
        return drawn;
    }

    /**
     * The sections the game has decided are visible, as mirror slots, in the
     * game's own order.
     *
     * Taken from the game rather than worked out: the first thing this port has
     * to prove is that it can draw what vanilla draws. A visibility search of
     * our own is an optimisation and comes later, with a measurement beside it.
     *
     * @return how many sections were written into {@link #slots}
     */
    private static int gather(ChunkSectionLayer layer) {
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        List<SectionRenderDispatcher.RenderSection> visible =
                ((LevelRendererAccess) renderer).vulkanmodnext$visibleSections();
        int count = 0;
        int at = 0;
        for (int i = 0; i < visible.size() && count < MAX_SECTIONS; i++) {
            SectionRenderDispatcher.RenderSection section = visible.get(i);
            SectionMesh mesh = section.getSectionMesh();
            if (mesh.isEmpty(layer) || !(mesh instanceof SectionMeshSlots owner)) {
                continue;
            }
            int slot = owner.vulkanmodnext$slot(layer.ordinal());
            if (slot == ChunkSlots.UNASSIGNED) {
                // Built before we were watching, or never built at all.
                continue;
            }
            if (at + 4 > slots.length) {
                slots = java.util.Arrays.copyOf(slots, slots.length * 2);
            }
            // Four numbers a section, not one: the slot says where its geometry
            // is, and the three after it say where in the world to put it. A
            // section's vertices are stored relative to its own corner, so
            // without these every one of them is drawn at the origin.
            BlockPos origin = section.getRenderOrigin();
            slots[at++] = slot;
            slots[at++] = origin.getX();
            slots[at++] = origin.getY();
            slots[at++] = origin.getZ();
            count++;
        }
        return count;
    }

    /**
     * The per-frame numbers the terrain shader needs beyond the geometry.
     *
     * <p>The sun is taken from the same angle the game draws its own sky from.
     * A renderer with a clock of its own drifts from the sky above it within a
     * day, and then a shadow points somewhere the light in the picture does not
     * come from.
     *
     * <p>The fog is the one thing this version cannot simply be asked for. On
     * 1.16.5 it is fixed-function state and is read back exactly as the game
     * set it; here it lives in a uniform buffer the game writes and never reads
     * again, so what is sent is a linear fog derived from the render distance —
     * the same shape vanilla uses, and honestly an approximation until the fog
     * buffer is read properly. Its colour is not approximated: that arrives as
     * a parameter of the world pass.
     */
    private static void handOverFrameState(VkContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            // The same fraction of a day the game itself puts in its global
            // uniform — day time modulo a day, plus the partial tick, over a
            // day. Worked out here rather than asked for because the method
            // that used to answer it is gone: on this version nothing exposes
            // the sun's angle outside the sky renderer, and a renderer with a
            // clock of its own drifts from the sky above it within a day.
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            float angle = ((mc.level.getDayTime() % 24000L) + partial) / 24000.0f
                    * ((float) Math.PI * 2.0f);
            sun[0] = -(float) Math.sin(angle);
            sun[1] = (float) Math.cos(angle);
            sun[2] = 0.0f;
            context.setSunDirection(sun);
            context.setRainStrength(mc.level.getRainLevel(partial));
        }

        float far = mc.options.getEffectiveRenderDistance() * 16.0f;
        fog[3] = 1.0f;          // linear
        fog[4] = far * 0.75f;   // start
        fog[5] = far;           // end
        fog[6] = 0.0f;          // density, unused while linear
        context.setFogState(fog);

        // Where the camera is, in the space the matrix maps to the origin. The
        // model-view carries the rotation only, so this is the rotation undone
        // on the translation, which for a rotation-only matrix is zero and is
        // still worth sending rather than assumed.
        cameraOffset[0] = -(frameModelView.m00() * frameModelView.m30()
                + frameModelView.m01() * frameModelView.m31()
                + frameModelView.m02() * frameModelView.m32());
        cameraOffset[1] = -(frameModelView.m10() * frameModelView.m30()
                + frameModelView.m11() * frameModelView.m31()
                + frameModelView.m12() * frameModelView.m32());
        cameraOffset[2] = -(frameModelView.m20() * frameModelView.m30()
                + frameModelView.m21() * frameModelView.m31()
                + frameModelView.m22() * frameModelView.m32());
        context.updateCameraOffset(cameraOffset);
    }

    /**
     * Which layers Vulkan is actually taking.
     *
     * Four numbers rather than one, because "the terrain is drawn" is four
     * separate claims and they fail separately.
     */
    public static String layerReport() {
        StringBuilder sb = new StringBuilder("layers taken by Vulkan:");
        String[] names = {"solid", "cutout", "frame close", "translucent"};
        for (int i = 0; i < 4; i++) {
            sb.append(' ').append(names[i]).append(' ').append(taken[i])
                    .append('/').append(taken[i] + refused[i]);
        }
        return sb.toString();
    }

    /**
     * Gives the renderer the atlas and the lightmap, once each.
     *
     * <h2>Once, and this is not an optimisation</h2>
     *
     * {@code updateAtlas} does not compare and skip: it destroys the copy it
     * has, forgets every sprite sheet built from it and uploads the whole chain
     * again. Calling that per layer is not four times the work, it is a stall —
     * the upload reuses the renderer's one-shot fence, and the second call in a
     * frame waits on a fence the frame it is inside has already taken. The
     * 1.16.5 port did exactly that and the report read {@code
     * vkWaitForFences(atlas) failed with VK_TIMEOUT}, which reads like a driver
     * problem and is not one.
     *
     * @return false when the lightmap does not exist yet, which happens for a
     *         few frames after a world opens
     */
    private static boolean handOverTextures(VkContext context) {
        int atlas = atlasTexture();
        int lightmap = lightmapTexture();
        if (lightmap <= 0 || atlas <= 0) {
            return false;
        }
        if (atlas != sentAtlas) {
            context.updateAtlas(atlas);
            sentAtlas = atlas;
        }
        if (lightmap != sentLightmap) {
            context.setLightmap(lightmap);
            sentLightmap = lightmap;
        }
        return true;
    }

    /** Which layer this is, in the order the renderer was written against. */
    private static int ordinalOf(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> LAYER_SOLID;
            case CUTOUT -> LAYER_CUTOUT_MIPPED;
            case TRANSLUCENT -> LAYER_TRANSLUCENT;
            case TRIPWIRE -> -1;
        };
    }

    /** The lightmap, by its OpenGL name, or 0 before the game has built one. */
    private static int lightmapTexture() {
        try {
            return RenderTargets.glName(Minecraft.getInstance().gameRenderer
                    .lightTexture().getTextureView().texture());
        } catch (Throwable notYet) {
            return 0;
        }
    }

    /** The block atlas, by its OpenGL name. */
    public static int atlasTexture() {
        try {
            AbstractTexture atlas = Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS);
            return RenderTargets.glName(atlas.getTexture());
        } catch (Throwable notYet) {
            return 0;
        }
    }

    /**
     * Projection times model-view, both taken from the game rather than from
     * OpenGL.
     *
     * On 1.16.5 the first attempt read them back with {@code glGetFloatv} and
     * got NaN and an identity, because the camera lives in the game's own
     * matrix stack. Here there is not even a fixed-function stack to read: the
     * matrices exist only as parameters, which is where these come from.
     */
    private static void modelViewProjection() {
        product.set(frameProjection).mul(frameModelView);
        product.get(mvp);
        // OpenGL's clip space runs from -1 to 1 in depth and Vulkan's from 0 to
        // 1. Without this correction every vertex is placed correctly in x and
        // y and lands outside the depth range, so the world is drawn and none
        // of it survives — which on 1.16.5 looked like eight hundred sections,
        // six hundred thousand vertices, and a screen full of sky.
        Matrices.toVulkanDepth(mvp);
    }

    /**
     * One line, once, saying whether the inputs make sense.
     *
     * The numbers to look at are the section count against what the game drew,
     * and the last row of the matrix: a perspective projection puts −1 in the
     * third column of it, and a matrix that arrives transposed or empty says so
     * there before it says it as a black screen.
     */
    private static void announceOnce(ChunkSectionLayer layer, int count) {
        if (announced || count == 0 || frames < ANNOUNCE_ON_FRAME) {
            return;
        }
        announced = true;
        VulkanModNext.LOGGER.info("Terrain layer {}: {} sections to draw", layer, count);
        VulkanModNext.LOGGER.info("  camera ({}, {}, {}), atlas texture {}, lightmap {}",
                String.format("%.1f", viewX), String.format("%.1f", viewY),
                String.format("%.1f", viewZ), atlasTexture(), lightmapTexture());
        VulkanModNext.LOGGER.info("  matrix last row {} {} {} {} (a perspective one has -1 third)",
                String.format("%.3f", mvp[3]), String.format("%.3f", mvp[7]),
                String.format("%.3f", mvp[11]), String.format("%.3f", mvp[15]));
    }
}
