package net.vulkanmodnext.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.IParticleRenderType;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.vector.Matrix4f;
import net.vulkanmodnext.mixin.ParticleManagerAccess;
import net.vulkanmodnext.vkimpl.VkContext;

import java.util.Map;
import java.util.Queue;

/**
 * The particle loop, run at the translucent layer and handed to Vulkan.
 *
 * This is vanilla's {@code ParticleManager.renderParticles} for the four render
 * types whose whole meaning is "quads from one sheet": the block atlas, and the
 * particle atlas opaque, lit and translucent. Every particle still builds its
 * own vertices through its own {@code render}, so a mod's particle of one of
 * those types is drawn by its own code exactly as before. See {@link Sprites}
 * for why this runs before the water rather than at the game's moment.
 *
 * <p>Every other type stays with the game, drawn where and how it always was:
 * {@code CUSTOM} (the item flying into your hand, the elder guardian's face)
 * renders models through the game's buffers, and a mod's own type may bind any
 * texture in its {@code begin}. Neither is a batch of quads from a sheet this
 * renderer holds.
 *
 * <p>What is lost, and it is worth saying rather than discovering: vanilla
 * writes depth for particles and draws the opaque sheets without blending.
 * The pass these are drawn in borrows the game's depth read-only and blends
 * everything, so two overlapping particles blend instead of one hiding the
 * other, at a scale of a few pixels — the same trade the 1.12.2 build makes.
 */
public final class ParticleHooks {

    /**
     * The types taken, the sheet each one samples and its alpha test.
     *
     * Opaque before translucent, which is the order that looks right when
     * nothing in the pass writes depth. Vanilla walks an identity map here and
     * its order is whatever the hash codes say.
     */
    private static final IParticleRenderType[] TYPES = {
            IParticleRenderType.TERRAIN_SHEET,
            IParticleRenderType.PARTICLE_SHEET_OPAQUE,
            IParticleRenderType.PARTICLE_SHEET_LIT,
            IParticleRenderType.PARTICLE_SHEET_TRANSLUCENT,
    };
    private static final int[] SLOTS = {
            Sprites.SLOT_BLOCK_ATLAS,
            Sprites.SLOT_PARTICLES,
            Sprites.SLOT_PARTICLES,
            Sprites.SLOT_PARTICLES,
    };
    private static final float[] CUTOFFS = {
            Sprites.DEFAULT_CUTOFF,
            Sprites.DEFAULT_CUTOFF,
            Sprites.DEFAULT_CUTOFF,
            Sprites.TRANSLUCENT_CUTOFF,
    };

    /** Which of {@link #TYPES} are in this frame's pass; the game skips those. */
    private static final boolean[] taken = new boolean[TYPES.length];

    /**
     * A builder of our own rather than the tessellator's. The shared one is
     * the game's, and this runs in the middle of a layer the game believes it
     * is drawing.
     */
    private static BufferBuilder builder;

    private static long framesTaken;
    private static long framesDeclined;
    private static long batches;
    private static long vertices;
    private static long refused;

    private ParticleHooks() {
    }

    /** Called at the top of every frame: last frame's answer is not this one's. */
    static void beginFrame() {
        java.util.Arrays.fill(taken, false);
    }

    /**
     * Builds this frame's particles and parks them for the translucent pass.
     *
     * @return how many batches were parked
     */
    static int capture(VkContext context, MatrixStack matrices, Matrix4f projection,
                       double viewX, double viewY, double viewZ) {
        if (!Sprites.usable(context, VulkanConfig.on("vulkanParticles"))) {
            return 0;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.particleEngine == null || mc.level == null) {
            return 0;
        }
        Map<IParticleRenderType, Queue<Particle>> particles =
                ((ParticleManagerAccess) mc.particleEngine).vulkanmodnext$particles();
        ActiveRenderInfo camera = mc.gameRenderer.getMainCamera();
        float partialTicks = mc.getFrameTime();
        // The frustum vanilla builds for this frame, built the same way from
        // the same two matrices and the same camera position. Its own copy is a
        // local of renderLevel and cannot be reached from here.
        ClippingHelper frustum = new ClippingHelper(matrices.last().pose(), projection);
        frustum.prepare(viewX, viewY, viewZ);
        if (builder == null) {
            builder = new BufferBuilder(256);
        }
        int parked = 0;
        for (int i = 0; i < TYPES.length; i++) {
            Queue<Particle> queue = particles.get(TYPES[i]);
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            builder.begin(7, DefaultVertexFormats.PARTICLE);
            try {
                for (Particle particle : queue) {
                    if (!particle.shouldCull() || frustum.isVisible(particle.getBoundingBox())) {
                        particle.render(builder, camera, partialTicks);
                    }
                }
                builder.end();
            } catch (Throwable t) {
                // Left half open, the builder would throw at the next begin;
                // it is ours, so it is simply emptied.
                if (builder.building()) {
                    builder.end();
                }
                builder.discard();
                Sprites.fail("Drawing particles", t);
                // The game draws this type, and every one after it, itself —
                // and will meet the same particle, and report it in its own
                // words, which is better than ours.
                return parked;
            }
            int took;
            try {
                took = Sprites.submitFinished(context, builder, SLOTS[i], CUTOFFS[i]);
            } catch (Throwable t) {
                builder.discard();
                Sprites.fail("Handing particles over", t);
                return parked;
            }
            if (took >= 0) {
                taken[i] = true;
                parked++;
                batches++;
                vertices += took;
            } else {
                // Refused: the game draws this type at its own moment.
                builder.discard();
                refused++;
            }
        }
        return parked;
    }

    /**
     * Whether the translucent pass actually ran. If it did not, whatever was
     * parked is thrown away with it, and the game has to draw every type.
     */
    static void settle(boolean drawn, int parked) {
        if (parked == 0) {
            return;
        }
        if (drawn) {
            framesTaken++;
            return;
        }
        framesDeclined++;
        java.util.Arrays.fill(taken, false);
    }

    /**
     * Asked by the game's own particle loop for each type.
     *
     * @return true when this type is already in the Vulkan pass this frame
     */
    public static boolean isTaken(IParticleRenderType type) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == type) {
                return taken[i];
            }
        }
        return false;
    }

    /**
     * Counted since the session began, for the flight report and diagnostics.
     *
     * "Frames taken" is the number to read: it counts passes in which the
     * translucent layer really went to Vulkan with particles in it. Batches
     * parked in a pass that then declined are counted as declined, not taken.
     */
    public static String stats() {
        if (!VulkanConfig.on("vulkanParticles")) {
            return "particles: drawn by the game (Vulkan particles are off)";
        }
        return String.format("particles: %d frames through Vulkan, %d declined back to the game, "
                        + "%d batches, %d vertices, %d batches refused%s",
                framesTaken, framesDeclined, batches, vertices, refused,
                Sprites.isBroken() ? " — path disabled after a failure" : "");
    }
}
