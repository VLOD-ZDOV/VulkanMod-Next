package net.vulkanmodnext.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderState;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.minecraft.client.renderer.texture.Texture;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.VulkanModNext;
import net.vulkanmodnext.mixin.RenderStateAccess;
import net.vulkanmodnext.mixin.RenderTypeAccess;
import net.vulkanmodnext.mixin.RenderTypeStateAccess;
import net.vulkanmodnext.mixin.TextureStateAccess;
import net.vulkanmodnext.vkimpl.VkContext;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Creature geometry, turned into the vertices the sprite pass already draws.
 *
 * <h2>Why there is no entity pipeline</h2>
 *
 * The same answer as on 1.12.2. The vertices a model part emits are in the
 * camera's frame — the view rotation is already in them — while everything
 * this renderer draws is in world axes measured from the camera. Undoing the
 * view once a frame costs one transposed rotation; then a creature's vertices
 * are in exactly the space particles and weather are in, and the pass that
 * draws those draws these too — same pipeline, same vertex format, same
 * composite, and the creature half of that pass writes depth.
 *
 * <h2>What is different on this version, and why it is simpler</h2>
 *
 * 1.12.2 had to rebuild every bone's placement itself: the placement lived in
 * OpenGL's matrix stack, asking the driver for it cost microseconds a bone,
 * and the fix was to mirror the stack and compose each bone's transform by
 * hand, checked against the driver two hundred and eighty thousand times. None
 * of that is needed here. The pose stack is the game's own object on the
 * processor, and a model part turns its boxes into finished vertices by
 * calling a builder it is handed.
 *
 * <p>So a part is taken by handing it a different builder. The game's own
 * {@code render} runs, with its own transforms, its own children and its own
 * rules about what is hidden, and the only thing that changes is where the
 * vertices land. There is no second copy of the skeleton walk to drift out
 * of step with the first — which is also why the composed-pose check the
 * 1.12.2 capture carried has no counterpart here.
 *
 * <h2>What travels with the vertex now</h2>
 *
 * Colour, light and the hurt overlay were all fixed-function state on 1.12.2
 * and had to be mirrored from the calls that set them (and a creature that
 * lost one of them was brighter, or never flashed red). Here all three are
 * arguments of the call and are written into every vertex — the tint of a
 * dyed sheep, the light it stands in including any carried torch
 * ({@code EntityLightMixin} raises that before it gets here), and the overlay.
 * The one thing still not in the vertex is the game's own two-light shading,
 * which vanilla does in the fixed pipeline and is folded into the colour below.
 *
 * <h2>What is not ours</h2>
 *
 * Anything that does not come through {@code ModelRenderer}; anything whose
 * builder is not one the game's buffer source handed out for a plain render
 * type (enchanted armour's glint, a glowing mob's outline, sprite-sheet models
 * like chests and shields all arrive wrapped); any render type that blends,
 * adds, or tests depth for equality; any skin that will not fit in a slot; and
 * everything outside the frame's entity pass. All of those are drawn by the
 * game exactly as they were, and the choice is made before the game's drawing
 * is cancelled, never after. That is the whole of the fallback, and it costs
 * nothing to have.
 *
 * <h2>What is still wrong, and why it is not fixed here</h2>
 *
 * A creature drawn here writes depth into Vulkan's copy of the frame, and that
 * depth does not come back to the game: on this version the depth image is
 * never shared, and the composite after the translucent pass puts down colour
 * only. Everything the game draws after that pass — particles, clouds, rain
 * and snow — is tested against a depth with no creatures in it, so rain
 * falling behind a sheep is drawn across it. On 1.12.2 particles and weather
 * go through the same pass after the creatures and the question never
 * arises. The cure is one of those two — sprites through Vulkan, or the depth
 * sent back — and both are on the Vulkan side, which this file cannot change.
 */
public final class EntityGeometry {

    /** pos 3f | uv 2f | colour 4ub | light 2s — vanilla's particle vertex. */
    private static final int VERTEX_BYTES = 28;

    /**
     * One buffer per skin, because the pass draws one texture at a time — and
     * per colour laid over that skin, because the overlay travels with the
     * batch rather than with the vertex, the same trade 1.12.2 made: the vertex
     * format is full, and a hurt creature costs one extra draw call while it is
     * red and nothing the rest of the time.
     */
    private static final class Batch {
        final int glTexture;
        /** Packed ARGB laid over the skin; 0 for the ordinary case. */
        final int overlay;
        /** The render type's own alpha test. */
        final float cutoff;
        /** The skin's sprite slot for this pass; 0 unresolved, -1 refused. */
        int slot;
        ByteBuffer vertices;
        int count;
        /** Whether anything was written here during the pass just gone. */
        boolean used;

        Batch(int glTexture, int overlay, float cutoff) {
            this.glTexture = glTexture;
            this.overlay = overlay;
            this.cutoff = cutoff;
            this.vertices = BufferUtils.createByteBuffer(64 * 1024).order(ByteOrder.nativeOrder());
        }
    }

    private static final List<Batch> BATCHES = new ArrayList<Batch>();

    /**
     * Which render type each builder was last handed out for, during this pass.
     *
     * Identity, because a builder's equality is not its identity for every
     * implementation and here only identity is the question. Emptied at both
     * ends of the pass, so a builder from a frame ago never names a type.
     */
    private static final IdentityHashMap<IVertexBuilder, RenderType> BUILDERS =
            new IdentityHashMap<IVertexBuilder, RenderType>();

    /**
     * What each render type is, worked out once: its alpha test when it is one
     * this draws, {@link #REFUSED} when it is not. Render types are interned by
     * the game, so this is as many entries as there are skins and kinds.
     */
    private static final IdentityHashMap<RenderType, float[]> KINDS =
            new IdentityHashMap<RenderType, float[]>();
    private static final IdentityHashMap<RenderType, ResourceLocation> TEXTURES =
            new IdentityHashMap<RenderType, ResourceLocation>();
    private static final float[] REFUSED = new float[0];

    /** Vanilla's {@code DEFAULT_ALPHA}: the cutout render types discard at one 255th. */
    private static final float DEFAULT_ALPHA = 0.003921569f;

    /** The builder a taken part is replayed into. There is one, and it is not re-entered. */
    private static final Capture CAPTURE = new Capture();

    /** Inside the entity pass of a frame whose translucent pass Vulkan will run. */
    private static boolean armed;
    /** Either this or the capture is listening; the hooks read this and nothing else. */
    private static boolean watching;

    /** The camera's rotation, undone, so vertices come out in world axes. */
    private static final float[] VIEW_INVERSE = new float[16];
    private static FloatBuffer poseScratch;
    private static final float[] POSE = new float[16];

    /**
     * The two lights the game shades every creature with, in world directions.
     *
     * {@code RenderHelper.setupLevel} hands OpenGL the same two directions
     * 1.12.2 used, transformed by the camera so they stay fixed in the world,
     * with 0.6 of diffuse each and an ambient of 0.4. The nether's second
     * light points down instead of up, and is chosen per pass because the
     * game chooses it per frame.
     */
    private static final float[] LIGHT_0 = normalised(0.2f, 1.0f, -0.7f);
    private static final float[] LIGHT_1 = normalised(-0.2f, 1.0f, 0.7f);
    private static final float[] NETHER_LIGHT_1 = normalised(-0.2f, -1.0f, 0.7f);
    private static final float DIFFUSE = 0.6f;
    private static final float AMBIENT = 0.4f;
    private static float[] light1 = LIGHT_1;

    /** Whether a batch went to the renderer this frame; see TerrainFrame's translucent layer. */
    private static boolean submittedThisFrame;

    private static long framesDrawn;
    private static long partsTaken;
    private static long quadsDrawn;
    private static long wrappedBuilders;
    private static long refusedTypes;
    private static long noSkin;
    private static long skinsRefused;
    private static long failedParts;
    private static long batchesRefused;
    private static long framesLost;
    private static boolean failureLogged;
    private static int lastQuads;
    private static int lastBatches;
    private static String standingAside = "";

    private EntityGeometry() {
    }

    private static float[] normalised(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / length, y / length, z / length};
    }

    /** One static read for the two hooks on the hottest paths of the pass. */
    public static boolean watching() {
        return watching;
    }

    /** From the top of every frame; see {@link #submittedThisFrame()}. */
    public static void newFrame() {
        submittedThisFrame = false;
    }

    /**
     * Whether creatures were handed to the renderer this frame.
     *
     * Asked by the translucent layer, which otherwise declines a frame with no
     * water or glass in view — and with it the pass the creatures are drawn in.
     * Particles in a desert went missing on 1.12.2 for this exact reason.
     */
    public static boolean submittedThisFrame() {
        return submittedThisFrame;
    }

    /**
     * Opens the window, if this frame is one Vulkan can draw creatures in.
     *
     * Everything that decides it is asked here, once, rather than per part:
     * whether the setting is on, whether the opaque layers of this very frame
     * went to Vulkan — the translucent pass that draws creatures only exists
     * after them — and whether the game is in Fabulous mode, where the
     * translucent layer is drawn into a target of its own and composed by a
     * shader of the game's that knows nothing of creatures.
     */
    public static void beginPass(MatrixStack matrices) {
        armed = false;
        BUILDERS.clear();
        EntityCapture.arm();
        try {
            String aside = whyNot();
            standingAside = aside;
            if (aside.isEmpty()) {
                armed = invertView(matrices);
                if (!armed) {
                    standingAside = "the camera matrix was not a plain rotation";
                }
            }
        } catch (Throwable failed) {
            armed = false;
            noteFailure("could not open the entity pass", failed);
        }
        if (armed) {
            Minecraft mc = Minecraft.getInstance();
            light1 = mc.level != null && mc.level.effects().constantAmbientLight()
                    ? NETHER_LIGHT_1 : LIGHT_1;
            // Plain batches are kept between frames: there are as many of them
            // as there are skins on screen, and each owns a buffer worth
            // keeping. Overlaid ones are not, because their key is a colour
            // that changes every tick a creature is hurt — kept, they would
            // collect a buffer per shade of red anything had ever flashed.
            for (int i = BATCHES.size() - 1; i >= 0; i--) {
                Batch batch = BATCHES.get(i);
                if (batch.overlay != 0 && !batch.used) {
                    BATCHES.remove(i);
                    continue;
                }
                batch.used = false;
                batch.count = 0;
                // Asked again every pass: a resource reload forgets every
                // sheet on the Vulkan side and renumbers the game's textures.
                batch.slot = 0;
            }
        }
        watching = armed || EntityCapture.armed();
    }

    /** Empty when creatures can go through Vulkan this frame, the reason when not. */
    private static String whyNot() {
        if (!VulkanConfig.on("vulkanEntities")) {
            return "off in the settings";
        }
        if (Minecraft.useShaderTransparency()) {
            return "Fabulous graphics composes its own translucent layer";
        }
        if (!TerrainFrame.opaqueTakenThisFrame()) {
            return "the terrain of this frame was drawn by the game";
        }
        if (!VulkanConfig.on("vulkanTranslucent")) {
            return "the translucent layer is left to the game (vulkanTranslucent off)";
        }
        VkContext context = VulkanStartup.context();
        if (context == null || !context.drawsSprites()) {
            return "the renderer has no sprite pass on this machine";
        }
        return "";
    }

    /**
     * Records which render type the entity pass's buffer source just handed a
     * builder out for. Only that source: the crumbling and outline sources are
     * different objects, and the GUI's are never inside the window.
     */
    public static void noteBuffer(IRenderTypeBuffer.Impl source, RenderType type,
                                  IVertexBuilder builder) {
        if (builder == null || type == null
                || source != Minecraft.getInstance().renderBuffers().bufferSource()) {
            return;
        }
        BUILDERS.put(builder, type);
    }

    /** The render type this builder is collecting for right now, or null. */
    static RenderType typeOf(IVertexBuilder builder) {
        return BUILDERS.get(builder);
    }

    /** Whether this is our own builder, which the capture must not count. */
    static boolean isOurs(IVertexBuilder builder) {
        return builder == CAPTURE;
    }

    /**
     * Draws one part and everything hanging off it, if it can be drawn here.
     *
     * @return true when the game should not draw it as well — only once all of
     *         it is in a batch. Every refusal is made before a single vertex is
     *         written, and a failure partway through takes back what it wrote,
     *         so the game draws the part whole or not at all.
     */
    public static boolean take(ModelRenderer part, MatrixStack matrices, IVertexBuilder builder,
                               int light, int overlay, float red, float green, float blue,
                               float alpha) {
        if (!armed || builder == CAPTURE || part == null || !part.visible) {
            // Our own replay passes straight through, and a hidden part draws
            // nothing whoever is asked to.
            return false;
        }
        RenderType type = BUILDERS.get(builder);
        if (type == null) {
            wrappedBuilders++;
            return false;
        }
        float cutoff = cutoffOf(type);
        if (cutoff != cutoff) {
            refusedTypes++;
            return false;
        }
        int glTexture = glTextureOf(type);
        if (glTexture <= 0) {
            noSkin++;
            return false;
        }
        Batch batch = batchFor(glTexture, overlayColour(overlay), cutoff);
        if (batch.slot == 0) {
            VkContext context = VulkanStartup.context();
            int slot = context == null ? 0 : context.spriteSlotForTexture(glTexture);
            batch.slot = slot > 0 ? slot : -1;
        }
        if (batch.slot < 0) {
            skinsRefused++;
            return false;
        }
        int start = batch.count;
        MatrixStack.Entry before = matrices.last();
        boolean whole = false;
        CAPTURE.begin(batch);
        try {
            // The game's own method, with our builder. Virtual on purpose: a
            // mod that overrides render and writes to the builder it is given
            // lands in the batch the same way vanilla does.
            part.render(matrices, CAPTURE, light, overlay, red, green, blue, alpha);
            whole = !CAPTURE.failed && (batch.count - start) % 4 == 0;
        } catch (Throwable failed) {
            // A throw between the part's push and its pop leaves the pose
            // stack a level deep, and the game checks it is empty after the
            // loop and stops the frame with an exception if not. Put back to
            // where it was before the game draws the part itself.
            for (int guard = 0; matrices.last() != before && guard < 64; guard++) {
                matrices.popPose();
            }
            noteFailure("a model part could not be drawn through Vulkan", failed);
        } finally {
            CAPTURE.end();
        }
        if (!whole || batch.count == start) {
            // Claimed only if something was written, and all of it. Answering
            // yes with an empty or broken batch cancels the game's drawing of
            // a creature nobody then draws.
            if (!whole) {
                failedParts++;
            }
            batch.count = start;
            return false;
        }
        batch.used = true;
        partsTaken++;
        quadsDrawn += (batch.count - start) / 4;
        return true;
    }

    private static void noteFailure(String what, Throwable failed) {
        if (!failureLogged) {
            failureLogged = true;
            VulkanModNext.LOGGER.warn("{}; the game keeps drawing it", what, failed);
        }
    }

    /**
     * The render type's own alpha test when it is one this draws, NaN when not.
     *
     * By name, because the name is the only thing that says what a render type
     * does. The four here are opaque, write depth and test it the ordinary way,
     * which is exactly what the creature half of the sprite pass does. What is
     * left out, and why:
     * <ul>
     * <li>{@code entity_translucent*}, {@code entity_no_outline}, {@code eyes},
     *     {@code energy_swirl}: blended or added, and the creature pipeline
     *     does neither.</li>
     * <li>{@code entity_decal}: tests depth for equality with what is already
     *     there, which is the game's depth, not ours.</li>
     * <li>{@code entity_smooth_cutout}: a half-way alpha test and smooth
     *     shading, for a handful of models; not worth a batch key.</li>
     * <li>{@code entity_cutout_no_cull_z_offset}: pushed towards the camera to
     *     win a depth fight against a surface it lies on, which this pass
     *     would lose.</li>
     * </ul>
     * The culled two are drawn without culling, as the creature pipeline
     * draws everything; for a closed box that is invisible, and for a skin
     * with holes in it the far side shows through the holes.
     */
    private static float cutoffOf(RenderType type) {
        float[] known = KINDS.get(type);
        if (known == null) {
            known = classify(type);
            KINDS.put(type, known);
        }
        return known.length == 0 ? Float.NaN : known[0];
    }

    private static float[] classify(RenderType type) {
        if (!(type instanceof RenderTypeAccess)) {
            return REFUSED;
        }
        String name = ((RenderStateAccess) type).vulkanmodnext$name();
        float cutoff;
        if ("entity_solid".equals(name)) {
            // No alpha test in vanilla; zero keeps every texel but the
            // entirely empty ones, which a solid skin does not have.
            cutoff = 0.0f;
        } else if ("entity_cutout".equals(name) || "entity_cutout_no_cull".equals(name)
                || "armor_cutout_no_cull".equals(name)) {
            cutoff = DEFAULT_ALPHA;
        } else {
            return REFUSED;
        }
        return textureLocation(type) == null ? REFUSED : new float[]{cutoff};
    }

    /**
     * The picture a render type binds, or null when it binds none or is not
     * one of the game's own render types. Cached: render types are interned.
     */
    static ResourceLocation textureLocation(RenderType type) {
        ResourceLocation known = TEXTURES.get(type);
        if (known != null) {
            return known == NO_TEXTURE ? null : known;
        }
        ResourceLocation found = null;
        if (type instanceof RenderTypeAccess) {
            RenderState.TextureState texture = ((RenderTypeStateAccess) (Object)
                    ((RenderTypeAccess) type).vulkanmodnext$state()).vulkanmodnext$textureState();
            Optional<ResourceLocation> where = texture == null
                    ? Optional.<ResourceLocation>empty()
                    : ((TextureStateAccess) texture).vulkanmodnext$texture();
            found = where.orElse(null);
        }
        TEXTURES.put(type, found == null ? NO_TEXTURE : found);
        return found;
    }

    /** Stands in the cache for "binds no texture", which a map cannot hold as null. */
    private static final ResourceLocation NO_TEXTURE =
            new ResourceLocation("vulkanmodnext", "no_texture");

    /**
     * The skin's OpenGL name, asked of the texture manager each time rather
     * than kept: a resource reload hands out new names for the same locations.
     * Zero when the texture is not loaded yet — the render type loads it when
     * the game draws the batch, so that creature is the game's for one frame.
     */
    private static int glTextureOf(RenderType type) {
        ResourceLocation where = textureLocation(type);
        if (where == null) {
            return 0;
        }
        Texture texture = Minecraft.getInstance().getTextureManager().getTexture(where);
        return texture == null ? 0 : texture.getId();
    }

    /**
     * The overlay coordinates, turned into the colour the game's overlay
     * texture holds there.
     *
     * {@code OverlayTexture} is a 16 by 16 table: the top half is red at 0.3
     * — a creature taking damage — and the bottom half is white growing from
     * nothing to 0.75 along u, the flash of a creeper about to go off. The game
     * reads it on a texture unit it configures to blend towards that colour by
     * the texel's alpha; the sprite pass takes the same thing as one ARGB
     * number with alpha as the strength, so the table is worked out here
     * instead of sampled.
     */
    private static int overlayColour(int packed) {
        int u = packed & 0xFFFF;
        int v = (packed >>> 16) & 0xFFFF;
        // The game scales the coordinates by one fifteenth and samples a
        // sixteen-texel table without filtering, clamped at the edge.
        int column = Math.min(15, u * 16 / 15);
        int row = Math.min(15, v * 16 / 15);
        if (row < 8) {
            // 0xB2 kept of the creature: 0x4D of red.
            return 0x4DFF0000;
        }
        int keep = (int) ((1.0f - column / 15.0f * 0.75f) * 255.0f);
        int strength = 255 - keep;
        return strength <= 0 ? 0 : (strength << 24) | 0xFFFFFF;
    }

    private static Batch batchFor(int glTexture, int tint, float cutoff) {
        Batch current = CAPTURE.lastBatch;
        if (current != null && current.glTexture == glTexture && current.overlay == tint
                && current.cutoff == cutoff) {
            return current;
        }
        for (int i = 0; i < BATCHES.size(); i++) {
            Batch batch = BATCHES.get(i);
            if (batch.glTexture == glTexture && batch.overlay == tint && batch.cutoff == cutoff) {
                CAPTURE.lastBatch = batch;
                return batch;
            }
        }
        Batch batch = new Batch(glTexture, tint, cutoff);
        BATCHES.add(batch);
        CAPTURE.lastBatch = batch;
        return batch;
    }

    /**
     * Takes the camera's rotation out, once for the whole pass.
     *
     * On this version the pose stack at the top of the world holds the view
     * rotation and nothing else — the camera's position is taken off each
     * entity's placement, and the view bob lives in the projection — so its
     * inverse is its transpose. A matrix that is not of that kind is refused
     * rather than inverted wrongly, because a wrong view puts every creature
     * in the world somewhere else.
     */
    private static boolean invertView(MatrixStack matrices) {
        if (poseScratch == null) {
            poseScratch = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }
        poseScratch.clear();
        matrices.last().pose().store(poseScratch);
        float[] m = POSE;
        poseScratch.get(m);
        float lengthSq = m[0] * m[0] + m[1] * m[1] + m[2] * m[2];
        if (m[3] != 0.0f || m[7] != 0.0f || m[11] != 0.0f || m[15] != 1.0f
                || lengthSq < 0.99f || lengthSq > 1.01f) {
            return false;
        }
        float[] out = VIEW_INVERSE;
        out[0] = m[0];
        out[1] = m[4];
        out[2] = m[8];
        out[3] = 0.0f;
        out[4] = m[1];
        out[5] = m[5];
        out[6] = m[9];
        out[7] = 0.0f;
        out[8] = m[2];
        out[9] = m[6];
        out[10] = m[10];
        out[11] = 0.0f;
        out[12] = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14]);
        out[13] = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14]);
        out[14] = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14]);
        out[15] = 1.0f;
        return true;
    }

    /**
     * Hands the pass over, one skin at a time, and closes the window.
     *
     * Closed here and not at the end of the frame: block entities, the
     * outline of the block being looked at and everything after are not
     * creatures, and a part taken there would be put in a batch that has
     * already gone.
     */
    public static void endPass() {
        EntityCapture.disarm();
        boolean wasArmed = armed;
        armed = false;
        watching = false;
        BUILDERS.clear();
        CAPTURE.lastBatch = null;
        if (!wasArmed) {
            lastQuads = 0;
            lastBatches = 0;
            return;
        }
        VkContext context = VulkanStartup.context();
        int batches = 0;
        int quads = 0;
        for (int i = 0; i < BATCHES.size(); i++) {
            Batch batch = BATCHES.get(i);
            if (batch.count == 0) {
                continue;
            }
            boolean taken = false;
            if (context != null && batch.slot > 0) {
                batch.vertices.position(0).limit(batch.count * VERTEX_BYTES);
                // A cutout threshold rather than a particle's: a creature's
                // skin is opaque where it is drawn at all.
                taken = context.submitSprites(batch.vertices, batch.count, batch.slot,
                        batch.cutoff, batch.overlay, false);
                batch.vertices.clear();
            }
            if (taken) {
                batches++;
                quads += batch.count / 4;
            } else {
                // Too late to hand back: the game's drawing of these parts was
                // cancelled when they were taken. The renderer refuses a batch
                // only past a million vertices a frame; counted so that if it
                // ever happens it is a number and not a missing creature.
                batchesRefused++;
            }
            batch.count = 0;
        }
        lastBatches = batches;
        lastQuads = quads;
        if (quads > 0) {
            submittedThisFrame = true;
            framesDrawn++;
        }
    }

    /** The end of the frame; a window still open here is closed without drawing. */
    public static void standDown() {
        if (armed || watching) {
            armed = false;
            watching = false;
            BUILDERS.clear();
            CAPTURE.lastBatch = null;
            for (int i = 0; i < BATCHES.size(); i++) {
                BATCHES.get(i).count = 0;
            }
        }
    }

    /**
     * The translucent layer went back to the game in a frame that held
     * creatures, and with it the pass they were to be drawn in.
     *
     * Everything that can make it do so is asked before the window opens, so
     * this should stay at zero; it is here because the one time it is not is
     * the time a player reports mobs flickering out, and a count is the only
     * witness.
     */
    public static void translucentRefused() {
        framesLost++;
    }

    /**
     * Vanilla's own shading of one vertex, folded into its colour.
     *
     * Done here for the reason it was done on 1.12.2: the normal is in hand at
     * this moment and the vertex format has no room to carry it, and the game
     * shades these flat, one normal per face, so there is nothing a
     * per-fragment version would add.
     *
     * @param colour R in the low byte through to alpha in the high one; alpha
     *               is not a colour and is left alone, because a creature's
     *               edges are decided by the alpha test and dimming it would
     *               move them
     */
    private static int shade(int colour, float nx, float ny, float nz) {
        float[] m = VIEW_INVERSE;
        float wx = m[0] * nx + m[4] * ny + m[8] * nz;
        float wy = m[1] * nx + m[5] * ny + m[9] * nz;
        float wz = m[2] * nx + m[6] * ny + m[10] * nz;
        float length = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (length < 1.0e-6f) {
            return colour;
        }
        wx /= length;
        wy /= length;
        wz /= length;
        float[] l1 = light1;
        float lit = AMBIENT
                + DIFFUSE * Math.max(wx * LIGHT_0[0] + wy * LIGHT_0[1] + wz * LIGHT_0[2], 0.0f)
                + DIFFUSE * Math.max(wx * l1[0] + wy * l1[1] + wz * l1[2], 0.0f);
        // OpenGL clamps the result of lighting, and a face square-on to both
        // lights reaches past one without it.
        if (lit > 1.0f) {
            lit = 1.0f;
        }
        int r = (int) ((colour & 0xFF) * lit);
        int g = (int) (((colour >>> 8) & 0xFF) * lit);
        int b = (int) (((colour >>> 16) & 0xFF) * lit);
        return (colour & 0xFF000000) | (b << 16) | (g << 8) | r;
    }

    private static int clampByte(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /**
     * The builder a taken part draws into.
     *
     * Vanilla's model parts call the fourteen-argument {@code vertex} once per
     * corner, which is overridden to write straight into the batch. The
     * one-attribute-at-a-time calls are there for a mod whose part builds its
     * vertices that way, and end in the same write.
     */
    private static final class Capture implements IVertexBuilder {
        private Batch batch;
        /** The batch picked last, tried first — consecutive parts share a skin. */
        Batch lastBatch;
        /** Set when a call arrived that this cannot represent; the part is refused. */
        boolean failed;

        private float x;
        private float y;
        private float z;
        private int colour = -1;
        private float u;
        private float v;
        private int lightU;
        private int lightV;
        private float nx;
        private float ny = 1.0f;
        private float nz;

        void begin(Batch into) {
            batch = into;
            failed = false;
        }

        void end() {
            batch = null;
        }

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue,
                           float alpha, float u, float v, int overlay, int light,
                           float nx, float ny, float nz) {
            // The game's own float to byte: truncating, as its colour(float)
            // does, so a tint lands on the same byte either way.
            int packed = clampByte((int) (red * 255.0f))
                    | clampByte((int) (green * 255.0f)) << 8
                    | clampByte((int) (blue * 255.0f)) << 16
                    | clampByte((int) (alpha * 255.0f)) << 24;
            write(x, y, z, packed, u, v, light & 0xFFFF, (light >>> 16) & 0xFFFF, nx, ny, nz);
        }

        @Override
        public IVertexBuilder vertex(double x, double y, double z) {
            this.x = (float) x;
            this.y = (float) y;
            this.z = (float) z;
            return this;
        }

        @Override
        public IVertexBuilder color(int red, int green, int blue, int alpha) {
            colour = clampByte(red) | clampByte(green) << 8 | clampByte(blue) << 16
                    | clampByte(alpha) << 24;
            return this;
        }

        @Override
        public IVertexBuilder uv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public IVertexBuilder overlayCoords(int u, int v) {
            // Already decided for the whole part: the batch carries it.
            return this;
        }

        @Override
        public IVertexBuilder uv2(int u, int v) {
            lightU = u;
            lightV = v;
            return this;
        }

        @Override
        public IVertexBuilder normal(float x, float y, float z) {
            nx = x;
            ny = y;
            nz = z;
            return this;
        }

        @Override
        public void endVertex() {
            write(x, y, z, colour, u, v, lightU, lightV, nx, ny, nz);
        }

        private void write(float x, float y, float z, int colour, float u, float v,
                           int lightU, int lightV, float nx, float ny, float nz) {
            Batch into = batch;
            if (into == null) {
                failed = true;
                return;
            }
            ensure(into);
            float[] m = VIEW_INVERSE;
            ByteBuffer out = into.vertices;
            int at = into.count * VERTEX_BYTES;
            out.putFloat(at, m[0] * x + m[4] * y + m[8] * z + m[12]);
            out.putFloat(at + 4, m[1] * x + m[5] * y + m[9] * z + m[13]);
            out.putFloat(at + 8, m[2] * x + m[6] * y + m[10] * z + m[14]);
            out.putFloat(at + 12, u);
            out.putFloat(at + 16, v);
            out.putInt(at + 20, shade(colour, nx, ny, nz));
            out.putShort(at + 24, (short) lightU);
            out.putShort(at + 26, (short) lightV);
            into.count++;
        }

        private static void ensure(Batch batch) {
            int needed = (batch.count + 1) * VERTEX_BYTES;
            if (batch.vertices.capacity() >= needed) {
                return;
            }
            int size = batch.vertices.capacity();
            while (size < needed) {
                size *= 2;
            }
            ByteBuffer grown = BufferUtils.createByteBuffer(size).order(ByteOrder.nativeOrder());
            batch.vertices.position(0).limit(batch.count * VERTEX_BYTES);
            grown.put(batch.vertices);
            batch.vertices.clear();
            grown.clear();
            batch.vertices = grown;
        }
    }

    public static String stats() {
        if (!VulkanConfig.on("vulkanEntities")) {
            return "vulkan entities: off";
        }
        return "vulkan entities: " + lastQuads + " quads in " + lastBatches + " skins last frame; "
                + framesDrawn + " frames drawn, " + partsTaken + " parts, " + quadsDrawn
                + " quads total"
                + (standingAside.isEmpty() ? "" : "; standing aside last frame: " + standingAside)
                + "; left to the game: " + wrappedBuilders + " parts with a wrapped builder "
                + "(glint, outline, sprite sheet), " + refusedTypes + " with a blended or "
                + "special render type, " + noSkin + " with no skin loaded yet"
                + (skinsRefused > 0 ? ", " + skinsRefused + " whose skin had no slot" : "")
                + (failedParts > 0 ? ", " + failedParts + " that could not be written" : "")
                + (batchesRefused > 0 ? "; " + batchesRefused + " batches the renderer refused "
                + "after the game's drawing was cancelled" : "")
                + (framesLost > 0 ? "; " + framesLost + " frames whose creatures were lost with "
                + "the translucent layer" : "");
    }
}
