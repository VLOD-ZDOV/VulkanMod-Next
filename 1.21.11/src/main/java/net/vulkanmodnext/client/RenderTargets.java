package net.vulkanmodnext.client;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * The OpenGL framebuffer behind one of the game's render targets, and the
 * OpenGL name of a texture the game owns.
 *
 * <h2>Why this is needed here and on neither of the other versions</h2>
 *
 * On 1.12.2 and 1.16.5 the game draws through OpenGL directly, so at the moment
 * a terrain layer starts the right framebuffer is already bound and a texture
 * is an {@code int} that can be asked for. From 1.21.5 on, Blaze3D has an
 * abstraction in front of the card — {@code GpuDevice}, {@code GpuTexture},
 * {@code RenderPass} — and a framebuffer only exists for the duration of a
 * render pass the game opens and we are cancelling.
 *
 * <p>So both have to be reached through the one implementation of that
 * abstraction that exists, which is the OpenGL one. That cast is the honest
 * shape of what this mod is: an OpenGL renderer standing beside the game's.
 * The day a second implementation appears — a Vulkan one, which is what the
 * abstraction is plainly for — this class is where the port finds out, because
 * the cast fails loudly here rather than quietly somewhere else.
 */
public final class RenderTargets {

    private static boolean complained;

    private RenderTargets() {
    }

    /**
     * Binds the framebuffer this target draws into and sets the viewport.
     *
     * @return the framebuffer that was bound before, to hand back to
     *         {@link #restore(int)}, or -1 when nothing could be bound and the
     *         caller must leave the frame to the game
     */
    public static int bind(RenderTarget target) {
        if (target == null) {
            return -1;
        }
        try {
            GpuTexture colour = target.getColorTexture();
            GpuTexture depth = target.getDepthTexture();
            if (!(target.getColorTextureView() instanceof GlTextureView view) || colour == null) {
                return -1;
            }
            GlDevice device = (GlDevice) RenderSystem.getDevice();
            int fbo = view.getFbo(device.directStateAccess(), depth);
            int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
            GL11C.glViewport(0, 0, target.width, target.height);
            return previous;
        } catch (Throwable notOpenGl) {
            if (!complained) {
                complained = true;
                net.vulkanmodnext.VulkanModNext.LOGGER.warn("The game is not drawing through "
                        + "OpenGL, so this renderer has nothing to composite into; the world "
                        + "stays with the game", notOpenGl);
            }
            return -1;
        }
    }

    public static void restore(int framebuffer) {
        if (framebuffer >= 0) {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        }
    }

    /** The OpenGL name behind one of the game's textures, or 0. */
    public static int glName(GpuTexture texture) {
        return texture instanceof GlTexture gl ? gl.glId() : 0;
    }
}
