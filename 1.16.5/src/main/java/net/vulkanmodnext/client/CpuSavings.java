package net.vulkanmodnext.client;

/**
 * The switches of the processor-side savings, and the counters that prove them.
 *
 * <h2>Read once a frame, not once a call</h2>
 *
 * The frustum test is called for every chunk the visibility search reaches,
 * tens of thousands of times a frame at a long render distance, and a setting
 * lookup is a map read on a string key. The switches are therefore settled at
 * the top of {@code renderLevel} and read from fields. A change made in the
 * menu takes effect on the next frame, which is the soonest anybody could see
 * it anyway.
 *
 * <h2>Counters beside each saving</h2>
 *
 * Every one of these is meant to leave the picture exactly as it was, so a
 * frame comparison can only ever say "nothing changed" — which is also what it
 * says when the saving never ran. The counters are the half of the proof that
 * can fail: each one counts the work the saving took over, or the work it left
 * behind, so a flight with the switch off and one with it on must disagree in
 * the number while agreeing in the picture. Render thread only, like
 * everything they count.
 */
public final class CpuSavings {

    public static boolean fastFrustumTest;

    /** Boxes tested against the frustum, and how many of those this mod answered. */
    private static long frustumTests;
    private static long frustumAnswered;

    private CpuSavings() {
    }

    /** Called at the top of {@code renderLevel}, before the visibility search. */
    public static void beginFrame() {
        fastFrustumTest = VulkanConfig.on("fastFrustumTest");
    }

    public static void countFrustumTest(boolean answered) {
        frustumTests++;
        if (answered) {
            frustumAnswered++;
        }
    }

    public static String stats() {
        return "cpu savings: frustum tests " + frustumTests + " (" + frustumAnswered
                + " by the far corner)";
    }
}
