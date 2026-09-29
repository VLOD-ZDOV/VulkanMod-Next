package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.vulkanmodnext.VulkanModNext;

/**
 * Caps the framerate while the window is not the active one.
 *
 * With the frame limit at its maximum ("unlimited") vanilla never sleeps, and
 * a minimised window keeps the GPU at full load rendering frames nobody can
 * see. The compositor does not throttle it either, because nothing is being
 * presented. Sleeping here costs nothing while the game is in front and gives
 * the whole GPU back the moment it is not.
 *
 * <h2>Why through the game's own limiter</h2>
 *
 * On 1.12.2 this slept with a {@code Display.sync} of its own after the frame.
 * Here the game's limiter, {@code RenderSystem.limitDisplayFPS}, keeps one
 * "last frame" clock for everybody, so a second sleep beside it would stack
 * with the game's instead of replacing it — a cap of ten under a frame limit
 * of 120 would come out at about nine. So the answer is given where the game
 * asks what its cap is, {@code Minecraft.getFramerateLimit}, and the game then
 * sleeps once, to the lower of the two.
 */
public final class BackgroundThrottle {

    private BackgroundThrottle() {
    }

    /**
     * Whether frames are being held back, told to the renderer.
     *
     * Without this the pacing report calls the cap a stall: at ten frames a
     * second every frame is a hundred milliseconds, so the worst frame, the
     * one percent low and the count of frames over three times the median all
     * describe a sleep this code asked for. That is the same trap as the drop
     * to five frames a second that cost a whole investigation once — a setting
     * working exactly as told, read as a fault.
     *
     * Published on the change rather than every frame: a property write per
     * frame to say nothing changed is a cost with no reader.
     */
    private static boolean published;

    /**
     * Called once per frame with the cap the game chose; returns the one to use.
     */
    public static int limit(int vanilla) {
        int limit = VulkanConfig.get("backgroundFpsLimit");
        // Not during a flight. A flight is a measurement, it pins every other
        // frame cap it knows of, and whether its window has focus is not up to
        // it — on a machine where somebody is using something else, it does
        // not. Three runs came back "median 10, 5% low 10, worst 10", a number
        // that flat is a cap and not a frame rate, and it was read as the
        // renderer being slow.
        boolean throttling = limit > 0
                && !Minecraft.getInstance().isWindowActive()
                && !Flight.asked();
        if (throttling != published) {
            published = throttling;
            System.setProperty("vulkanmodnext.frameThrottled", Boolean.toString(throttling));
            // Not one of the PUBLISHED settings, so the stamp the renderer
            // watches has to be moved by hand or it never re-reads this.
            VulkanConfig.settingsMoved();
            // Once per change, so a run can show the cap took hold without
            // anyone being able to watch an unfocused window's frame counter.
            VulkanModNext.LOGGER.info(throttling
                    ? "Window inactive: frames capped at " + limit + " a second"
                    : "Window active: background frame cap lifted");
        }
        return throttling ? Math.min(vanilla, limit) : vanilla;
    }
}
