package net.vulkanmodnext.vkimpl;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * The three things the OpenGL half of this renderer used that 1.21 does not
 * have.
 *
 * <h2>Why this class exists on this version and on neither of the others</h2>
 *
 * Minecraft asks GLFW for a <b>3.3 core profile, forward compatible</b> context
 * — the hints are in {@code Window}, and there are three of them: version 3.3,
 * profile {@code CORE}, forward compat true. The 1.12.2 and 1.16.5 builds get a
 * compatibility context and every legacy call in the renderer works there. Here
 * the same calls are not merely discouraged, they are removed: they raise
 * {@code GL_INVALID_OPERATION} and draw nothing.
 *
 * <p>Three of them are used, and each gets a replacement here rather than in
 * the copied files — the Vulkan half is generated from the 1.12.2 sources and a
 * hand edit in a copy is undone, silently, the next time the port script runs.
 *
 * <ol>
 * <li><b>The fullscreen quad.</b> Seven passes draw one with {@code glBegin} and
 *     four {@code glVertex2f}. Here it is one triangle in a buffer, drawn
 *     through a vertex array object — a triangle and not a quad because a
 *     single triangle that covers the screen has no diagonal seam across the
 *     middle, where two have one and every driver interpolates across it
 *     slightly differently.</li>
 * <li><b>{@code glPushAttrib} / {@code glPopAttrib}.</b> The attribute stack is
 *     gone, so the state is saved by asking for it. What is saved is the list
 *     below, which is what those passes actually change; a state this renderer
 *     never touches is not worth a driver round trip to read back.</li>
 * <li><b>The alpha test.</b> Core profile has none — it is the fragment
 *     shader's job. Every use in the renderer is a <em>disable</em>, so the
 *     replacement is honestly empty.</li>
 * </ol>
 *
 * <h2>What this class is careful about</h2>
 *
 * Minecraft's own {@code GlStateManager} caches OpenGL state and will not
 * re-send a value it believes is already set. Everything here therefore puts
 * back exactly what it found, including the vertex array binding and the
 * program: leaving one of ours bound means the game's next draw call goes
 * through our vertex layout, which is not an error and not a crash — it is a
 * screen of garbage several frames later.
 */
public final class GlCompat {

    /**
     * One triangle, big enough to cover clip space.
     *
     * (-1,-1), (3,-1), (-1,3): the part inside the screen is exactly the same
     * area two triangles of a quad would cover, and the rest is clipped away
     * for nothing. The texture coordinate is derived in the shader from the
     * position, so the buffer carries positions only.
     */
    private static final float[] FULLSCREEN_TRIANGLE = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    private static int vao = -1;
    private static int vbo = -1;

    /** Saved by {@link #pushState()}; one level deep, which is all that is used. */
    private static boolean savedBlend;
    private static boolean savedDepthTest;
    private static boolean savedCull;
    private static boolean savedScissor;
    private static boolean savedDepthMask;
    private static int savedDepthFunc;
    private static int savedBlendSrcRgb;
    private static int savedBlendDstRgb;
    private static int savedBlendSrcAlpha;
    private static int savedBlendDstAlpha;
    private static int savedProgram;
    private static int savedActiveTexture;
    private static int savedVertexArray;
    private static final int[] savedTextures = new int[5];
    private static final int[] savedViewport = new int[4];
    private static int depth;

    private GlCompat() {
    }

    /**
     * Draws a triangle covering the whole framebuffer.
     *
     * The caller has already chosen the program, the textures and the depth
     * state; this only supplies the geometry, which is the one part that could
     * not survive the profile change.
     */
    public static void drawFullscreenQuad() {
        ensureGeometry();
        int previousArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        GL30C.glBindVertexArray(vao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        GL30C.glBindVertexArray(previousArray);
    }

    /**
     * The attribute location the fullscreen vertex shader must use.
     *
     * Bound rather than queried so that the program does not have to be linked
     * before the buffer is set up, and named here so the shader and the buffer
     * cannot drift apart — which is the failure this project has already had
     * once between two halves of a vertex layout, and it took three thousand
     * frames to show.
     */
    public static final int POSITION_LOCATION = 0;

    private static void ensureGeometry() {
        if (vao != -1) {
            return;
        }
        int previousArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        vao = GL30C.glGenVertexArrays();
        vbo = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, FULLSCREEN_TRIANGLE, GL15C.GL_STATIC_DRAW);
        GL20C.glEnableVertexAttribArray(POSITION_LOCATION);
        GL20C.glVertexAttribPointer(POSITION_LOCATION, 2, GL11C.GL_FLOAT, false, 8, 0L);
        GL30C.glBindVertexArray(previousArray);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
    }

    /**
     * Reads back the state the passes below are about to change.
     *
     * Nested calls are counted and only the outermost pair does anything, which
     * matches what the attribute stack did for the one case in the renderer
     * where a pass that pushes calls another that also pushes.
     */
    public static void pushState() {
        if (depth++ > 0) {
            return;
        }
        savedBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        savedDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        savedCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        savedScissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        savedDepthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        savedDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        savedBlendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        savedBlendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        savedBlendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        savedBlendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        savedProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        savedActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        savedVertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, savedViewport);
        for (int unit = 0; unit < savedTextures.length; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            savedTextures[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        }
        GL13C.glActiveTexture(savedActiveTexture);
    }

    /** Puts back everything {@link #pushState()} read. */
    public static void popState() {
        if (--depth > 0) {
            return;
        }
        depth = 0;
        for (int unit = 0; unit < savedTextures.length; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, savedTextures[unit]);
        }
        GL13C.glActiveTexture(savedActiveTexture);
        GL20C.glUseProgram(savedProgram);
        GL30C.glBindVertexArray(savedVertexArray);
        GL11C.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        setEnabled(GL11C.GL_BLEND, savedBlend);
        setEnabled(GL11C.GL_DEPTH_TEST, savedDepthTest);
        setEnabled(GL11C.GL_CULL_FACE, savedCull);
        setEnabled(GL11C.GL_SCISSOR_TEST, savedScissor);
        GL11C.glDepthMask(savedDepthMask);
        GL11C.glDepthFunc(savedDepthFunc);
        GL14C.glBlendFuncSeparate(savedBlendSrcRgb, savedBlendDstRgb,
                savedBlendSrcAlpha, savedBlendDstAlpha);
    }

    /**
     * Draws the texture bound on unit 0 over the whole framebuffer.
     *
     * The startup probe used to do this with fixed-function texturing: bind,
     * {@code glEnable(GL_TEXTURE_2D)}, and a textured quad with no shader at
     * all. There is no such thing here — a core context samples only from a
     * shader — and the probe is the one place where quietly drawing nothing
     * would be worst of all, because its whole job is to answer "can this
     * driver read what Vulkan shared with it". A probe that cannot fail is not
     * a probe.
     */
    public static void sampleTextureFullscreen() {
        int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(sampleProgram());
        drawFullscreenQuad();
        GL20C.glUseProgram(previousProgram);
    }

    private static int sampleProgram = -1;

    private static int sampleProgram() {
        if (sampleProgram != -1) {
            return sampleProgram;
        }
        int vertex = compile(GL20C.GL_VERTEX_SHADER,
                "#version 330 core\n"
                        + "layout(location = 0) in vec2 aPos;\n"
                        + "out vec2 vUv;\n"
                        + "void main() {\n"
                        + "  vUv = aPos * 0.5 + 0.5;\n"
                        + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
                        + "}\n");
        int fragment = compile(GL20C.GL_FRAGMENT_SHADER,
                "#version 330 core\n"
                        + "uniform sampler2D uSource;\n"
                        + "in vec2 vUv;\n"
                        + "out vec4 fragColor;\n"
                        + "void main() { fragColor = texture(uSource, vUv); }\n");
        sampleProgram = GL20C.glCreateProgram();
        GL20C.glAttachShader(sampleProgram, vertex);
        GL20C.glAttachShader(sampleProgram, fragment);
        GL20C.glLinkProgram(sampleProgram);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        int previous = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        GL20C.glUseProgram(sampleProgram);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(sampleProgram, "uSource"), 0);
        GL20C.glUseProgram(previous);
        return sampleProgram;
    }

    private static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        return shader;
    }

    /**
     * Deliberately empty.
     *
     * Core profile has no alpha test at all, and every use of it in this
     * renderer is a disable — so on this version the state it was turning off
     * does not exist and there is nothing to do. Kept as a call rather than
     * deleted from the copied files, because deleting it there would have to be
     * done again on every re-port, and because a reader of the 1.12.2 source
     * finding nothing here would wonder what happened to it.
     */
    public static void disableAlphaTest() {
    }

    private static void setEnabled(int capability, boolean on) {
        if (on) {
            GL11C.glEnable(capability);
        } else {
            GL11C.glDisable(capability);
        }
    }

    /** Frees the quad. Called when the renderer tears its OpenGL objects down. */
    public static void destroy() {
        if (vao != -1) {
            GL30C.glDeleteVertexArrays(vao);
            GL15C.glDeleteBuffers(vbo);
            vao = -1;
            vbo = -1;
        }
        if (sampleProgram != -1) {
            GL20C.glDeleteProgram(sampleProgram);
            sampleProgram = -1;
        }
        depth = 0;
    }
}
