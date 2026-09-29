package net.vulkanmodnext.client;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.vulkanmodnext.VulkanModNext;

/**
 * How many particles one tick's explosions may ask for.
 *
 * <h2>What this is about, on this version</h2>
 *
 * On 1.12.2 the client asked for two particles per destroyed block, and a
 * tonne of TNT was tens of thousands of them in a single tick. 1.16.5 no longer
 * does that: an explosion asks for one particle at its centre. For a real
 * blast that one is an invisible emitter, {@code HugeExplosionParticle}, which
 * then asks for six large explosion puffs every tick for eight ticks — 48 per
 * charge. Two hundred charges going off together is still more than a
 * thousand puffs born every tick for the better part of half a second, each of
 * them an object that is ticked, sorted and drawn for its whole life. So the
 * budget is applied to what the emitters ask for, which is where the count is.
 *
 * <h2>Why a budget and not a switch</h2>
 *
 * Turning explosion particles off makes a blast look like a block edit. What
 * costs the frame is the count, and the count is what carries almost none of
 * the impression: the first few hundred puffs are the explosion, and the rest
 * are inside them.
 *
 * So the first {@code limit} of a tick are kept whole, and past that one in
 * eight — thinned rather than cut off, because a hard stop puts the missing
 * particles all in one place and leaves a hole where the blast was biggest.
 *
 * <h2>Client only</h2>
 *
 * Nothing here changes what breaks, what drops, or what the server thinks
 * happened. The blocks are already gone before this is asked.
 */
public final class ExplosionParticles {

    /** Asked for and kept this tick, and the same totals for the report. */
    private static int asked;
    private static int kept;
    private static long askedTotal;
    private static long keptTotal;
    private static int worstTick;
    private static int ticksSinceReport;

    /**
     * How often the totals are written to the log, in ticks. This version has
     * no diagnostics report to put them in, and a line per tick of a blast
     * would bury everything else; one every five seconds, and only when
     * something exploded, is enough to show the budget biting.
     */
    private static final int REPORT_TICKS = 100;

    private ExplosionParticles() {
    }

    /**
     * @return whether this particle should be spawned at all
     */
    public static boolean allow() {
        asked++;
        boolean allowed = keep(asked, VulkanConfig.get("explosionParticles"));
        if (allowed) {
            kept++;
        }
        return allowed;
    }

    /**
     * The rule itself, with the counting and the settings taken away.
     *
     * Separate so it can be checked against its own description without a
     * client: that a limit of zero keeps everything, that the first
     * {@code limit} of a tick are kept whole, and that past the limit exactly
     * one in eight survives. The last of those is the part that decides
     * whether a blast looks thinned or looks holed, and it is one bitwise and
     * away from being wrong in a way nobody would see until a screenshot.
     *
     * @param askedSoFar which request this is within the tick, counting from 1
     * @param limit      how many are kept whole; 0 or less is no limit at all
     */
    static boolean keep(int askedSoFar, int limit) {
        if (limit <= 0) {
            return true;
        }
        return askedSoFar <= limit || (askedSoFar & 7) == 0;
    }

    /** Called once per client tick, before anything can explode in it. */
    public static void newTick() {
        if (asked > worstTick) {
            worstTick = asked;
        }
        askedTotal += asked;
        keptTotal += kept;
        asked = 0;
        kept = 0;
        if (++ticksSinceReport < REPORT_TICKS) {
            return;
        }
        ticksSinceReport = 0;
        if (askedTotal == 0) {
            return;
        }
        VulkanModNext.LOGGER.info(
                "explosion particles: {} asked for, {} spawned, worst single tick {} (limit {})",
                askedTotal, keptTotal, worstTick, VulkanConfig.get("explosionParticles"));
        askedTotal = 0;
        keptTotal = 0;
        worstTick = 0;
    }

    public static final class Handler {

        /**
         * At the start of the tick: the budget is per tick, so it is cleared
         * where ticks are counted, and before the particle engine ticks the
         * emitters that spend it.
         */
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                newTick();
            }
        }
    }
}
