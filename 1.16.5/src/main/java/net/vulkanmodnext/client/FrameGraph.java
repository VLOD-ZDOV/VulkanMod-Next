package net.vulkanmodnext.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Arrays;

/**
 * A frame-time graph in the corner, in the shape DXVK draws one.
 *
 * <h2>Why an average framerate is the wrong number</h2>
 *
 * The number the game already shows is frames divided by seconds, and it
 * cannot tell a steady 120 from a 240 that stalls every tenth frame — both
 * average out the same, and only one of them is pleasant to play. What tells
 * them apart is the shape of the distribution, so this reports the worst
 * frames rather than the mean: the 1% low (the frame time only 1% of frames
 * exceed, quoted as a framerate), the best and worst single frame in the
 * window, and the graph itself, one bar per frame, so a periodic hitch is
 * visible as a pattern rather than inferred from a number.
 *
 * <h2>What it costs</h2>
 *
 * Nothing while it is off: recording is skipped entirely, so there is not even
 * a timestamp per frame.
 *
 * With it on, a frame pays one {@code nanoTime}, one array write, and one draw
 * call for the background and every bar together — {@code AbstractGui.fill}
 * would submit two hundred and forty of them, each with its own blend state
 * round trip. The statistics need the window sorted, which is the only part
 * that is more than trivial, so it is recomputed at the interval the setting
 * gives instead of every frame; text that changes faster than that cannot be
 * read anyway.
 *
 * It also times itself, and {@link #stats()} prints what it cost, because on
 * 1.12.2 "one draw call, so it costs nothing" measured at 0.2 to 0.5 ms a frame
 * the first time anyone looked. A measurement tool that quietly distorts the
 * thing it measures would be worse than none.
 *
 * <p>No per-pass GPU numbers here, on either version: the graph is the frame as
 * the player feels it, from the end of one frame to the end of the next, and it
 * is meant to work the same on the OpenGL fallback where there are no Vulkan
 * timers to read.
 */
public final class FrameGraph {

    /** Frames kept. At 120 fps this is about two seconds of history. */
    private static final int SAMPLES = 240;
    private static final int WIDTH = SAMPLES;
    private static final int HEIGHT = 40;
    private static final int MARGIN = 4;
    /** Room the two lines of text need, so a top corner leaves space for them. */
    private static final int TEXT_BAND = 22;

    public static final int CORNER_BOTTOM_LEFT = 0;
    public static final int CORNER_BOTTOM_RIGHT = 1;
    public static final int CORNER_TOP_LEFT = 2;
    public static final int CORNER_TOP_RIGHT = 3;

    private static final int COLOUR_MIN = 0x55C355;
    private static final int COLOUR_MAX = 0xE0A040;
    private static final int COLOUR_BACKGROUND = 0xE0000000;
    private static final int COLOUR_TRACE = 0xFFFFFFFF;
    private static final int COLOUR_BASELINE = 0x60FFFFFF;

    private static final int[] frameMicros = new int[SAMPLES];
    private static int writeIndex;
    private static int filled;
    private static long lastFrameNanos;

    private static final int[] sorted = new int[SAMPLES];
    private static long lastStatsNanos;
    private static int statMin;
    private static int statMax;
    private static int statAverage;
    private static int statOnePercentLow;

    /** Self-measurement, split in two because the fix depends on which half it is. */
    private static long drawNanos;
    private static long barNanos;
    private static long textNanos;
    private static long drawFrames;

    private FrameGraph() {
    }

    /**
     * One frame has passed. Called from the render tick whether or not the
     * Vulkan renderer came up, so the graph works on the OpenGL fallback too.
     */
    public static void record() {
        if (!VulkanConfig.on("frameGraph")) {
            // Drop the reference point as well: coming back from disabled must
            // not record one enormous frame covering the whole time it was off.
            lastFrameNanos = 0L;
            filled = 0;
            writeIndex = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            long elapsed = now - lastFrameNanos;
            frameMicros[writeIndex] = (int) Math.min(elapsed / 1000L, Integer.MAX_VALUE);
            writeIndex = (writeIndex + 1) % SAMPLES;
            if (filled < SAMPLES) {
                filled++;
            }
        }
        lastFrameNanos = now;
    }

    public static void draw(MatrixStack stack) {
        if (!VulkanConfig.on("frameGraph") || filled < 2) {
            return;
        }
        long started = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();

        refreshStats(started);

        // Which corner, and where the two lines of text go relative to the
        // graph. The default corner is the one the chat window uses, and a
        // frame-time readout sitting on top of what someone is reading is a
        // tool nobody leaves on. Text goes above the graph at the bottom of
        // the screen and below it at the top, so neither runs off the edge.
        int corner = VulkanConfig.get("frameGraphCorner");
        boolean right = corner == CORNER_BOTTOM_RIGHT || corner == CORNER_TOP_RIGHT;
        boolean atTop = corner == CORNER_TOP_LEFT || corner == CORNER_TOP_RIGHT;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int left = right ? screenWidth - MARGIN - WIDTH : MARGIN;
        int top = atTop ? MARGIN + TEXT_BAND : screenHeight - MARGIN - HEIGHT;
        int bottom = top + HEIGHT;
        int textNear = atTop ? bottom + 3 : top - 21;
        int textFar = atTop ? bottom + 14 : top - 10;

        // Drawn as deviation from the middle rather than as bars standing on
        // the floor. A frame quicker than the window's average goes down, a
        // slower one goes up, and a perfectly even scene is a flat line — which
        // is the thing worth seeing at a glance. Bars from the floor spend most
        // of their height saying "the framerate is roughly what the number
        // above already said", and the interesting part is the wobble on top.
        int centre = top + HEIGHT / 2;
        int baseline = Math.max(statAverage, 1);
        int spread = Math.max(Math.max(statMax - baseline, baseline - statMin), 500);

        long barsStarted = System.nanoTime();
        Matrix4f pose = stack.last().pose();
        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuilder();
        buffer.begin(7, DefaultVertexFormats.POSITION_COLOR);
        // The background goes in the same buffer, first, so it is under the
        // bars and still costs no draw call of its own.
        quad(buffer, pose, left - 1, top - 1, left + WIDTH + 1, bottom + 1, COLOUR_BACKGROUND);
        quad(buffer, pose, left, centre, left + WIDTH, centre + 1, COLOUR_BASELINE);
        for (int i = 0; i < filled; i++) {
            int micros = frameMicros[(writeIndex - filled + i + SAMPLES * 2) % SAMPLES];
            int offset = (micros - baseline) * (HEIGHT / 2) / spread;
            offset = Math.max(-HEIGHT / 2, Math.min(HEIGHT / 2, offset));
            int x = left + i * WIDTH / SAMPLES;
            if (offset >= 0) {
                quad(buffer, pose, x, centre - offset, x + 1, centre + 1, COLOUR_TRACE);
            } else {
                quad(buffer, pose, x, centre, x + 1, centre - offset + 1, COLOUR_TRACE);
            }
        }
        tessellator.end();
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
        barNanos += System.nanoTime() - barsStarted;

        long textStarted = System.nanoTime();
        FontRenderer font = mc.font;

        // Laid out the way DXVK lays its frame-time readout out: the best and
        // the worst frame of the window named and coloured, sitting on the two
        // ends of the graph they describe, rather than run together into one
        // line of grey text where neither stands out.
        String min = String.format("min: %.1f ms", statMin / 1000.0);
        String max = String.format("max: %.1f ms", statMax / 1000.0);
        font.drawShadow(stack, min, left, textFar, COLOUR_MIN);
        font.drawShadow(stack, max, left + WIDTH - font.width(max), textFar, COLOUR_MAX);

        // The average is what every framerate counter already shows. The 1% low
        // is the one that separates a steady 120 from a 240 that stalls, so it
        // is kept beside it rather than left to the log.
        String rate = String.format("%d fps", statAverage == 0 ? 0 : 1_000_000 / statAverage);
        String low = String.format("1%% low: %d fps",
                statOnePercentLow == 0 ? 0 : 1_000_000 / statOnePercentLow);
        font.drawShadow(stack, rate, left, textNear, 0xFFFFFF);
        font.drawShadow(stack, low, left + WIDTH - font.width(low), textNear, 0xB0B0B0);

        textNanos += System.nanoTime() - textStarted;
        drawNanos += System.nanoTime() - started;
        drawFrames++;
    }

    private static void refreshStats(long now) {
        // Read every time rather than cached: the setting is a slider, and a
        // slider that only takes effect on the next world load is a bad slider.
        long interval = VulkanConfig.get("frameGraphIntervalMs") * 1_000_000L;
        if (now - lastStatsNanos < interval && statMax != 0) {
            return;
        }
        lastStatsNanos = now;
        int n = filled;
        // Copied out oldest-first into a scratch array rather than sorting the
        // ring in place: sorting the ring would destroy the order the graph is
        // drawn in, and sorting the whole array while it is still filling would
        // let the untouched zeros become the minimum.
        for (int i = 0; i < n; i++) {
            sorted[i] = frameMicros[(writeIndex - n + i + SAMPLES * 2) % SAMPLES];
        }
        Arrays.sort(sorted, 0, n);
        statMin = sorted[0];
        statMax = sorted[n - 1];
        long total = 0L;
        for (int i = 0; i < n; i++) {
            total += sorted[i];
        }
        statAverage = (int) (total / n);
        // The frame time 99% of frames come in under. Everything above it is
        // the worst one per cent, which is where a stall lives and where an
        // average hides it.
        int index = Math.max(0, Math.min(n - 1, (int) Math.ceil(n * 0.99) - 1));
        statOnePercentLow = sorted[index];
    }

    private static void quad(BufferBuilder buffer, Matrix4f pose, int x0, int y0, int x1, int y1,
                             int argb) {
        float a = (argb >>> 24) / 255.0f;
        float r = (argb >> 16 & 255) / 255.0f;
        float g = (argb >> 8 & 255) / 255.0f;
        float b = (argb & 255) / 255.0f;
        buffer.vertex(pose, x0, y1, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, x1, y1, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, x1, y0, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, x0, y0, 0.0F).color(r, g, b, a).endVertex();
    }

    /** Reads and resets, like the other counters, so a snapshot covers one interval. */
    public static String stats() {
        if (drawFrames == 0) {
            return "frame graph: off";
        }
        String line = String.format(
                "frame graph: %.3f ms per frame (bars %.3f, text %.3f) over %d frames",
                drawNanos / 1_000_000.0 / drawFrames, barNanos / 1_000_000.0 / drawFrames,
                textNanos / 1_000_000.0 / drawFrames, drawFrames);
        drawNanos = 0L;
        barNanos = 0L;
        textNanos = 0L;
        drawFrames = 0L;
        return line;
    }

    /**
     * Drives the graph.
     *
     * Registered whether or not Vulkan came up, because the graph is a measuring
     * instrument and the case where it is most wanted is the one where the
     * renderer fell back to OpenGL and something is wrong.
     */
    public static final class Handler {

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                record();
            }
        }

        @SubscribeEvent
        public void onHudRendered(RenderGameOverlayEvent.Post event) {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
                draw(event.getMatrixStack());
            }
        }

        /**
         * Draws the graph on the screens where the game's overlay never runs.
         *
         * The overlay is drawn only with a world loaded, so on the main menu
         * nothing would draw this at all; this fills that gap. A menu opened
         * over a world is left out, as on 1.12.2: the overlay has already drawn
         * the graph under it, and whether it should also sit on top of the menu
         * is an open question there that has not been settled.
         */
        @SubscribeEvent
        public void onScreenDrawn(GuiScreenEvent.DrawScreenEvent.Post event) {
            if (Minecraft.getInstance().level != null) {
                return;
            }
            draw(event.getMatrixStack());
        }
    }
}
