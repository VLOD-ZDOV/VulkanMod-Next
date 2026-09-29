package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.vulkanmodnext.VulkanModNext;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many threads build chunk geometry.
 *
 * <h2>Two numbers on this version, not one</h2>
 *
 * On 1.12.2 the dispatcher started its own worker threads and the count was one
 * {@code clamp} in its constructor. On 1.16.5 it starts none. It hands every
 * build to the executor {@code WorldRenderer.allChanged} gives it —
 * {@code Util.backgroundExecutor()}, the pool the game shares with world
 * loading and everything else in the background, sized
 * {@code clamp(cores - 1, 1, 7)} — and the count its constructor works out
 * is only how many builder buffers it allocates, which is how many builds may
 * be <em>in flight</em>:
 *
 * <pre>
 * int i = Math.max(1, (int)(maxMemory * 0.3) / (sum of the layers' buffer sizes * 4) - 1);
 * int k = is64Bit ? cores : Math.min(cores, 4);
 * int l = Math.max(1, Math.min(k, i));
 * </pre>
 *
 * So a large CPU gets as many buffers as it has cores, and seven threads to
 * fill them with however many cores there are. Raising the buffer count alone
 * would change nothing; this raises both. A setting above zero gives the
 * dispatcher a pool of its own with exactly that many threads, and that many
 * buffers to go with them — still under vanilla's heap ceiling {@code i}, so
 * the heap keeps the last word on memory. Threads past it find no free buffer
 * and wait, which is why overshooting is wasteful rather than harmful.
 *
 * <h2>When it takes effect</h2>
 *
 * The dispatcher is made once, when a world is set on the renderer, and
 * {@code allChanged} — F3+A, a changed render distance — keeps the one it has.
 * So a changed value needs the dispatcher thrown away and made again, which is
 * exactly what the renderer does when the world changes: {@link #tick} does
 * that, once the settings screen is closed rather than on every step of a
 * dragged slider, since each one rebuilds the whole world.
 *
 * Raising this cannot help a machine whose cores are already all in use. The
 * default leaves vanilla's executor and buffer count untouched.
 */
public final class ChunkBuildThreads {

    /** The setting the current dispatcher was made under; -1 before any. */
    private static volatile int builtWith = -1;
    private static ThreadPoolExecutor pool;
    private static int poolSize;

    private ChunkBuildThreads() {
    }

    /**
     * The executor the dispatcher about to be made will build on: the game's
     * own at 0, otherwise a pool of the configured size, made again when that
     * size changes.
     *
     * The pool it replaces is not shut down. Builds already handed to it
     * finish there and report to a dispatcher that has been disposed, which
     * ignores them — the same thing that happens to builds in flight when the
     * world changes — and a shut-down pool would instead throw at whichever
     * of them tried to hand on its next step. Its threads time out once idle.
     */
    public static synchronized Executor executor(Executor vanilla) {
        int threads = VulkanConfig.get("chunkBuildThreads");
        builtWith = threads;
        if (threads <= 0) {
            return vanilla;
        }
        if (pool == null || poolSize != threads) {
            AtomicInteger number = new AtomicInteger();
            ThreadPoolExecutor made = new ThreadPoolExecutor(threads, threads,
                    30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), work -> {
                        Thread thread = new Thread(work,
                                "VulkanModNext Chunk Builder " + number.incrementAndGet());
                        // Never what keeps the game from exiting.
                        thread.setDaemon(true);
                        return thread;
                    });
            made.allowCoreThreadTimeOut(true);
            pool = made;
            poolSize = threads;
        }
        return pool;
    }

    /**
     * How many builder buffers the dispatcher allocates.
     *
     * @param cores    vanilla's CPU-side figure, {@code k} above
     * @param heapCeiling vanilla's heap-side figure, {@code i} above
     */
    public static int builders(int cores, int heapCeiling) {
        int vanilla = Math.min(cores, heapCeiling);
        int threads = VulkanConfig.get("chunkBuildThreads");
        if (threads <= 0) {
            return vanilla;
        }
        int builders = Math.min(threads, heapCeiling);
        VulkanModNext.LOGGER.info("Chunk building on {} threads with {} buffers instead of "
                        + "vanilla's shared pool with {} ({} cores, heap ceiling {})",
                threads, Math.max(1, builders), Math.max(1, vanilla),
                Runtime.getRuntime().availableProcessors(), heapCeiling);
        return builders;
    }

    /**
     * Makes the dispatcher again once the setting has moved away from the one
     * it was made under. Called each client tick while a world is open and no
     * screen is, so a dragged slider costs one rebuild, after it is let go.
     */
    public static void tick() {
        int wanted = VulkanConfig.get("chunkBuildThreads");
        if (builtWith < 0 || wanted == builtWith) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null || mc.level == null) {
            return;
        }
        VulkanModNext.LOGGER.info("Chunk build threads changed from {} to {}: making the "
                + "chunk builder again", builtWith, wanted);
        // The world-change path, both halves: dropping the level disposes the
        // dispatcher and nulls it, setting it again makes a new one through
        // allChanged, which is the only place one is made.
        mc.levelRenderer.setLevel(null);
        mc.levelRenderer.setLevel(mc.level);
    }
}
