package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The one place this mod is called every client tick.
 *
 * At the moment it holds a single job: open the development world once the
 * game has finished starting. It waits for the main menu rather than acting at
 * load, because the game is not ready to be told to load a world until it is
 * showing something.
 */
public final class ClientTicks {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // First, and it takes the whole run when it is asked for: the flight
        // makes its own world, so leaving the development world to also open one
        // would race it for the same client.
        if (Flight.asked()) {
            Flight.tick();
            return;
        }
        if (Minecraft.getInstance().screen != null || Minecraft.getInstance().level == null) {
            DevWorld.openIfAsked();
            return;
        }
        // Outside a screen, so a slider dragged there costs one rebuild after
        // it is let go rather than one per step.
        ChunkBuildThreads.tick();
        // In a world, with the frame just finished: the one moment the picture
        // can be read back and compared against the same route without us.
        FrameProbe.endFrame();
    }
}
