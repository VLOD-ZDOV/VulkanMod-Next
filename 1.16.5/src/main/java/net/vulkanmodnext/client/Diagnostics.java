package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.vulkanmodnext.vkimpl.VkContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11C;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Verbose diagnostics written to their own file, so a report can be handed over
 * whole instead of reconstructed from questions.
 *
 * The header is written once and covers the things that decide whether this mod
 * can work at all: versions, installed mods, the OpenGL driver. After that a
 * snapshot is appended on a timer with the live state of the renderer and the
 * settings that affect it, and this mod's own log is mirrored in between, so
 * the file says both what the state was and what the mod did to get there.
 *
 * Carried over from 1.12.2 with the same file name, the same shape and the
 * same rules. What it prints is what this port has: the per-layer account of
 * who drew the terrain, the renderer's own diagnostics, the chunk mirror, and
 * the counters of the effects that are ported. The 1.12.2 lines about parts of
 * the frame that do not exist here yet are left out rather than printed empty,
 * because an empty line reads as "measured, and nothing happened".
 *
 * Off by default; nothing here runs unless it is enabled — by the setting or
 * by {@code -Dvulkanmodnext.ultraLog=true}, which the settings already honour.
 */
public final class Diagnostics {

    private static final Logger LOGGER = LogManager.getLogger("VulkanModNext/Diagnostics");
    private static final String FILE_NAME = "vulkanmodnext-diagnostics.log";
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss");

    private static PrintWriter writer;
    private static boolean failed;
    private static boolean headerWritten;
    private static long lastSnapshotNanos;

    /**
     * Guards the writer and the backlog. Snapshots come from the render thread,
     * but mirrored log lines come from whichever thread wrote them — chunk
     * builders included — so the two cannot be allowed to interleave mid-line.
     */
    private static final Object LOCK = new Object();

    /**
     * Lines this mod logged before the file existed.
     *
     * Everything worth reading about a failed start happens during startup:
     * which device was chosen, what the driver reported, where initialization
     * gave up. The file is opened on the first frame, which is far too late for
     * any of it, so the lines are held until then. Bounded because a mod that
     * never gets to draw must not also fill the heap.
     */
    private static final java.util.ArrayDeque<String> BACKLOG = new java.util.ArrayDeque<String>();
    private static final int BACKLOG_LIMIT = 4000;
    private static boolean capturing;

    private Diagnostics() {
    }

    public static boolean enabled() {
        return VulkanConfig.on("ultraLog");
    }

    /**
     * Starts mirroring this mod's own log into the diagnostics file.
     *
     * Only lines from this mod are taken. The game's log keeps everything else,
     * and a diagnostics file with the whole of Forge in it would be no easier
     * to read than what it replaced.
     */
    public static void startCapture() {
        if (capturing || failed || !enabled()) {
            return;
        }
        capturing = true;
        try {
            org.apache.logging.log4j.core.LoggerContext context =
                    (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
            org.apache.logging.log4j.core.config.Configuration config = context.getConfiguration();
            @SuppressWarnings("deprecation")
            org.apache.logging.log4j.core.Appender mirror =
                    new org.apache.logging.log4j.core.appender.AbstractAppender(
                            "VulkanModNextDiagnostics", null, null, true) {
                        @Override
                        public void append(org.apache.logging.log4j.core.LogEvent event) {
                            mirror(event);
                        }
                    };
            mirror.start();
            config.getRootLogger().addAppender(mirror, org.apache.logging.log4j.Level.ALL, null);
            context.updateLoggers();
        } catch (Throwable t) {
            // A logging convenience is never worth taking the mod down for.
            capturing = false;
            LOGGER.warn("Could not mirror the mod's log into the diagnostics file", t);
        }
    }

    /** The shape of the run being folded, and what it has swallowed so far. */
    private static String repeatShape;
    private static String repeatLastLine;
    private static int repeatCount;

    /**
     * Folds a run of lines that say the same thing with different numbers.
     *
     * What repeats in a timing log is the sentence, not the line, so digits
     * are replaced by a mark to get at that sentence. The first line of a run
     * is written as-is, and the rest are folded to a count and the last of
     * them. Only consecutive lines fold: anything else between them is itself
     * the reason to stop folding.
     *
     * @return true when the line has been swallowed and must not be written
     */
    private static boolean foldRepeat(String text) {
        String shape = shapeOf(text);
        if (shape.equals(repeatShape)) {
            repeatCount++;
            repeatLastLine = text;
            return true;
        }
        flushRepeat();
        repeatShape = shape;
        repeatLastLine = null;
        repeatCount = 0;
        return false;
    }

    /** Writes out whatever a finished run swallowed. Call before anything else. */
    private static void flushRepeat() {
        if (repeatCount > 0 && writer != null) {
            writer.println("  ... the line above repeated " + repeatCount
                    + " more times, the last of them:");
            writer.println(repeatLastLine);
        }
        repeatCount = 0;
        repeatShape = null;
        repeatLastLine = null;
    }

    private static String shapeOf(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inNumber = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9' || (inNumber && (c == '.' || c == ':'))) {
                if (!inNumber) {
                    out.append('#');
                    inNumber = true;
                }
            } else {
                out.append(c);
                inNumber = false;
            }
        }
        return out.toString();
    }

    private static void mirror(org.apache.logging.log4j.core.LogEvent event) {
        String name = event.getLoggerName();
        if (name == null || !name.startsWith("VulkanModNext")) {
            return;
        }
        StringBuilder line = new StringBuilder()
                .append('[').append(stamp()).append("] ")
                .append(event.getLevel()).append(' ')
                .append(name).append(": ")
                .append(event.getMessage().getFormattedMessage());
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            java.io.StringWriter trace = new java.io.StringWriter();
            thrown.printStackTrace(new PrintWriter(trace));
            line.append(System.lineSeparator()).append(trace);
        }
        String text = line.toString();
        synchronized (LOCK) {
            if (writer != null) {
                if (foldRepeat(text)) {
                    return;
                }
                writer.println(text);
                writer.flush();
            } else if (BACKLOG.size() < BACKLOG_LIMIT) {
                BACKLOG.add(text);
            }
        }
    }

    /** Called once per frame; writes at most one snapshot per interval. */
    public static void tick() {
        if (failed || !enabled()) {
            return;
        }
        // Usually switched on in the middle of a session, after something has
        // gone wrong — and the capture at startup found the setting off and
        // never attached. On 1.12.2 a tester's file arrived with snapshots and
        // not one line of the mod's own log, which was the half that says what
        // they did. Cheap to repeat; it returns at once once attached.
        startCapture();
        long now = System.nanoTime();
        long intervalNanos = VulkanConfig.get("ultraLogSeconds") * 1_000_000_000L;
        if (headerWritten && now - lastSnapshotNanos < intervalNanos) {
            return;
        }
        lastSnapshotNanos = now;
        try {
            PrintWriter out = open();
            if (out == null) {
                return;
            }
            synchronized (LOCK) {
                flushRepeat();
                emitSnapshot(out);
                out.flush();
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("Diagnostics logging disabled after an error", t);
        }
    }

    /** Marks an event in the file and forces a snapshot on the next frame. */
    public static void flushNow(String reason) {
        if (failed || !enabled()) {
            return;
        }
        lastSnapshotNanos = 0;
        try {
            PrintWriter out = open();
            if (out != null) {
                // Under the lock, and after any folded run is written out: an
                // event line written between a run and its summary reads as
                // though it happened before the lines it followed.
                synchronized (LOCK) {
                    flushRepeat();
                    out.println("[" + stamp() + "] EVENT: " + reason);
                    out.flush();
                }
            }
        } catch (Throwable ignored) {
            // Diagnostics must never be the reason something breaks.
        }
    }

    /** {@code SimpleDateFormat} is not thread-safe, and mirrored lines come from any thread. */
    private static String stamp() {
        synchronized (STAMP) {
            return STAMP.format(new Date());
        }
    }

    private static PrintWriter open() throws IOException {
        if (writer != null) {
            return writer;
        }
        File directory = new File(Minecraft.getInstance().gameDirectory, "logs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            failed = true;
            return null;
        }
        Writer file = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(directory, FILE_NAME), false),
                StandardCharsets.UTF_8);
        synchronized (LOCK) {
            writer = new PrintWriter(file);
            // Header first, then everything the mod said before the file
            // existed, in the order it said it, and only then the first snapshot.
            writeHeader(writer);
            headerWritten = true;
            while (!BACKLOG.isEmpty()) {
                writer.println(BACKLOG.poll());
            }
            writer.flush();
        }
        return writer;
    }

    private static void writeHeader(PrintWriter out) {
        out.println("VulkanModNext diagnostics");
        out.println("========================");
        out.println("started: " + new Date());
        out.println("minecraft: " + net.minecraftforge.versions.mcp.MCPVersion.getMCVersion()
                + ", forge: " + net.minecraftforge.versions.forge.ForgeVersion.getVersion());
        out.println("java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        // os.name is not to be believed on Windows past 8 under Java 8: without
        // a manifest saying it knows the newer versions, the program is told
        // "Windows 8.1" on 10 and 11 alike. Said out loud so nobody explains a
        // bug with the wrong operating system.
        String os = System.getProperty("os.name");
        boolean lies = os != null && os.startsWith("Windows")
                && (os.contains("8") || os.contains("7"));
        out.println("os: " + os + " " + System.getProperty("os.arch")
                + (lies ? " (as Java 8 sees it — Windows hides its real version"
                        + " from programs this old, so 10 and 11 both show up here)" : "")
                + ", version " + System.getProperty("os.version"));
        out.println();

        out.println("mods:");
        try {
            ModList.get().getMods().forEach(mod ->
                    out.println("  " + mod.getModId() + " " + mod.getVersion()));
        } catch (Throwable t) {
            out.println("  (mod list unavailable: " + t + ")");
        }
        out.println();

        out.println("opengl:");
        out.println("  renderer: " + safeGlString(GL11C.GL_RENDERER));
        out.println("  vendor: " + safeGlString(GL11C.GL_VENDOR));
        out.println("  version: " + safeGlString(GL11C.GL_VERSION));
        out.println();
    }

    private static String safeGlString(int name) {
        try {
            String value = GL11C.glGetString(name);
            return value == null ? "(null)" : value;
        } catch (Throwable t) {
            return "(unavailable: " + t + ")";
        }
    }

    /**
     * The counters the F3 overlay shows, taken from the same methods it calls.
     *
     * On 1.12.2 their absence cost a day: a frame breakdown said this mod was
     * four percent of the frame, and nothing in the report said what the other
     * ninety-six were doing. Every call here is one the game itself makes each
     * frame while F3 is open, so none of it is work it would not otherwise do.
     */
    private static void writeVanillaCounters(PrintWriter out, Minecraft mc) {
        if (mc.level == null || mc.levelRenderer == null) {
            return;
        }
        try {
            out.println("  f3 chunks: " + mc.levelRenderer.getChunkStatistics());
            out.println("  f3 entities: " + mc.levelRenderer.getEntityStatistics());
            out.println("  f3 particles/entities: P: " + mc.particleEngine.countParticles()
                    + ". E: " + mc.level.getEntityCount());
            out.println("  f3 world: " + mc.level.gatherChunkSourceStats());
        } catch (Throwable t) {
            out.println("  f3: unavailable (" + t + ")");
        }
    }

    /** The last snapshot, line by line, so the next one can print only what moved. */
    private static String[] previousSnapshot;
    private static int snapshotsSinceFull;
    /**
     * How often the whole picture is written out anyway. A file of differences
     * alone is unreadable to somebody who opened it in the middle, and the
     * interesting part of a report is usually the end.
     */
    private static final int FULL_EVERY = 10;

    /**
     * Writes a snapshot as the difference from the one before it.
     *
     * Most of a snapshot never changes — the settings the session was
     * configured with are the same line for line, minute after minute — and a
     * file of them is the same information spread far enough apart that
     * reading it means scrolling past what has not moved to find what has.
     * Rendered into memory first and compared line by line, so no line has to
     * remember what it said last time.
     */
    private static void emitSnapshot(PrintWriter out) {
        java.io.StringWriter buffer = new java.io.StringWriter(8192);
        PrintWriter into = new PrintWriter(buffer);
        writeSnapshot(into);
        into.flush();
        String[] lines = buffer.toString().split("\r?\n", -1);
        boolean full = previousSnapshot == null || ++snapshotsSinceFull >= FULL_EVERY;
        if (full) {
            snapshotsSinceFull = 0;
            for (String line : lines) {
                out.println(line);
            }
        } else {
            int unchanged = 0;
            for (int i = 0; i < lines.length; i++) {
                String was = i < previousSnapshot.length ? previousSnapshot[i] : null;
                // The timestamp at the head changes every time, so each block
                // still begins with the line that dates it.
                if (lines[i].equals(was)) {
                    unchanged++;
                    continue;
                }
                out.println(lines[i]);
            }
            out.println("  (" + unchanged + " lines the same as last time, "
                    + (FULL_EVERY - snapshotsSinceFull) + " to the next full one)");
        }
        previousSnapshot = lines;
    }

    private static void writeSnapshot(PrintWriter out) {
        Minecraft mc = Minecraft.getInstance();
        out.println("[" + stamp() + "] snapshot");
        out.println("  fps: " + mc.fpsString
                + " | world: " + (mc.level == null ? "none" : "loaded")
                + ", gui: " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()));
        // Before the numbers, not after: every number below is about a scene,
        // and this is the only line that says which scene.
        out.println("  " + cameraLine(mc));
        if (VulkanConfig.isTerrainEnabled()) {
            // Read first and on its own line: if a layer went back to the game,
            // every timing below it is the game's and not this renderer's.
            for (String line : TerrainFrame.layerReport().split("\n")) {
                out.println("  " + line.trim());
            }
        } else {
            out.println("  terrain: drawn by the game (Vulkan terrain is off)");
        }
        out.println("  " + MaterialRuns.stats());
        out.println("  " + DynamicLights.stats());
        out.println("  " + ParticleHooks.stats());
        out.println("  " + WeatherHooks.stats());
        out.println("  " + BlockLightSources.stats());
        out.println("  " + FrameGraph.stats());
        // What this run was actually configured as. Without it a number has to
        // be matched to a configuration from memory.
        out.println("  " + nonDefaultSettings());
        writeVanillaCounters(out, mc);

        VkContext context = VulkanStartup.context();
        if (context == null) {
            out.println("  vulkan: not initialized");
        } else {
            try {
                out.println("  vulkan: " + context.gpuSummary() + ", "
                        + context.vramMegabytes() + " MiB device-local");
                out.println("  " + context.chunkMirrorStats());
                for (String line : context.terrainDiagnostics().split("\n")) {
                    if (!line.trim().isEmpty()) {
                        out.println("  " + line.trim());
                    }
                }
            } catch (Throwable t) {
                out.println("  vulkan: report failed: " + t);
            }
        }

        out.println("  settings: render distance " + mc.options.renderDistance
                + ", mipmaps " + mc.options.mipmapLevels
                + ", graphics " + mc.options.graphicsMode
                + ", vsync " + mc.options.enableVsync
                + ", fps limit " + mc.options.framerateLimit
                + ", particles " + mc.options.particles
                + ", entity shadows " + mc.options.entityShadows);
        out.println("  memory: " + used() + " MiB used of " + max() + " MiB, collectors "
                + JvmPauses.collections() + " runs, " + JvmPauses.collectionMillis() + " ms");
        out.println("  jvm: " + jvmLine());
        out.println();
    }

    private static String cameraLine(Minecraft mc) {
        if (mc.player == null) {
            return "camera: no player";
        }
        return String.format(java.util.Locale.ROOT, "camera: %.1f %.1f %.1f yaw %.1f pitch %.1f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.yRot, mc.player.xRot);
    }

    /**
     * Only the settings that differ from their default, asked of the settings
     * table rather than listed here: a hand-written list on 1.12.2 named three
     * settings that had stopped being under test and neither of the two that were.
     */
    private static String nonDefaultSettings() {
        StringBuilder line = new StringBuilder("changed settings:");
        int changed = 0;
        for (Settings.Setting setting : Settings.all()) {
            int value = VulkanConfig.get(setting.key);
            if (value == setting.fallback) {
                continue;
            }
            line.append(changed == 0 ? " " : ", ").append(setting.key).append('=')
                    .append(setting.bool ? String.valueOf(value != 0) : String.valueOf(value));
            changed++;
        }
        if (changed == 0) {
            line.append(" none, everything at its default");
        }
        return line.toString();
    }

    /**
     * How the virtual machine was started, and which collector it chose. On
     * 1.12.2 a session was spent looking in the renderer for a stutter that
     * belonged to the machine's default collector, and this one line would
     * have said so.
     */
    private static String jvmLine() {
        StringBuilder out = new StringBuilder();
        try {
            java.lang.management.RuntimeMXBean runtime =
                    java.lang.management.ManagementFactory.getRuntimeMXBean();
            out.append(System.getProperty("java.version", "?"))
                    .append(' ').append(System.getProperty("java.vm.name", "?"));
            java.util.List<String> args = runtime.getInputArguments();
            StringBuilder flags = new StringBuilder();
            for (String arg : args) {
                // Paths and user names live in -D and agent arguments, and this
                // file is written to be sent to somebody else.
                if (arg.startsWith("-D") || arg.startsWith("-javaagent")
                        || arg.startsWith("-agentlib") || arg.contains("/")
                        || arg.contains("\\")) {
                    continue;
                }
                if (flags.length() > 0) {
                    flags.append(' ');
                }
                flags.append(arg);
            }
            out.append(", args: ").append(flags.length() == 0 ? "none given" : flags);
            for (java.lang.management.GarbageCollectorMXBean gc
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                out.append(", collector: ").append(gc.getName());
            }
        } catch (Throwable t) {
            out.append("could not be asked: ").append(t.toString());
        }
        return out.toString();
    }

    private static long used() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    private static long max() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    /**
     * Drives the file: one check a frame, on the render thread, with or without
     * a world — the case where the file is most wanted is a start that never
     * got as far as one.
     */
    public static final class Handler {

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                tick();
            }
        }

        /** A world coming or going is the context every snapshot after it needs. */
        @SubscribeEvent
        public void onWorldLoad(WorldEvent.Load event) {
            if (event.getWorld().isClientSide()) {
                flushNow("world loaded");
            }
        }

        @SubscribeEvent
        public void onWorldUnload(WorldEvent.Unload event) {
            if (event.getWorld().isClientSide()) {
                flushNow("world unloaded");
            }
        }
    }
}
