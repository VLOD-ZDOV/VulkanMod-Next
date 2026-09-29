package net.vulkanmodnext.core;

import java.util.Locale;

/**
 * The one question this mod has to answer before it loads anything: can the
 * Vulkan half exist on this machine at all.
 *
 * <h2>Why it is a class of its own</h2>
 *
 * Two very different places need the answer, and one of them is the coremod,
 * which runs before the mod exists. So this carries no logger, no Minecraft
 * types and no fields — a class the launch classloader can define at any moment
 * without dragging anything in behind it.
 *
 * <h2>Why the architecture decides it</h2>
 *
 * This mod ships the native libraries LWJGL 3 needs for 64-bit x86 and nothing
 * else, so on anything else there is no Vulkan half to load. That is not an
 * oversight waiting to be corrected by adding more natives: the frame is handed
 * back to the game through memory shared between Vulkan and OpenGL, which needs
 * {@code EXT_memory_object_fd} and {@code EXT_semaphore_fd} on the OpenGL side,
 * and the translation layers that run this game on a phone provide neither.
 * A report from one such device is what prompted this being said out loud.
 */
public final class Platform {

    private Platform() {
    }

    /**
     * What about this machine rules the Vulkan half out, or null when nothing
     * does. Android first, because that answer holds whatever the processor:
     * PojavLauncher and its forks also run on 64-bit x86 tablets and
     * Chromebooks, and there the architecture check alone would let the loader
     * try desktop Linux libraries against Android's C library.
     */
    public static String unsupportedPlatform() {
        if (android()) {
            return "Android (" + System.getProperty("os.arch", "unknown") + ")";
        }
        return unsupportedArchitecture();
    }

    /**
     * Whether the game is running on Android, under PojavLauncher or one of the
     * launchers built from it.
     *
     * Those report {@code os.name} as plain Linux, so the question is asked of
     * what they cannot hide: the environment PojavLauncher sets up for its JVM,
     * and the system partition every Android device has and no desktop does.
     * Each check is only a lookup, and one that is refused counts as no.
     */
    public static boolean android() {
        try {
            if (System.getenv("POJAV_NATIVEDIR") != null || System.getenv("POJAV_RENDERER") != null) {
                return true;
            }
        } catch (SecurityException ignored) {
            // Not allowed to look is not evidence either way.
        }
        try {
            return new java.io.File("/system/build.prop").isFile()
                    || new java.io.File("/system/bin/app_process").exists();
        } catch (SecurityException ignored) {
            return false;
        }
    }

    /**
     * The processor this build has no Vulkan half for, or null when it does.
     *
     * Permissive on purpose. An unfamiliar name is far more likely to be 64-bit
     * x86 spelled unusually than a machine this cannot run on, and refusing one
     * of those would be a regression for somebody the code never met — so only
     * the families that are certainly not it are named.
     */
    public static String unsupportedArchitecture() {
        String arch = System.getProperty("os.arch", "");
        String lower = arch.toLowerCase(Locale.ROOT);
        if (lower.contains("amd64") || lower.contains("x86_64") || lower.contains("x64")) {
            return null;
        }
        if (lower.startsWith("aarch64") || lower.startsWith("arm") || lower.contains("riscv")) {
            return arch;
        }
        return null;
    }
}
