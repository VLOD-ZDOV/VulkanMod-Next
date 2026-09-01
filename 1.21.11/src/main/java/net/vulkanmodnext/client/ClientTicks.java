package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.event.TickEvent;

/**
 * The one place this mod is called every client tick.
 *
 * At the moment it holds two jobs: open the development world once the game has
 * finished starting, and read the finished frame back when there is one. It
 * waits for the main menu rather than acting at load, because the game is not
 * ready to be told to load a world until it is showing something.
 *
 * <h2>Subscribed by hand, not by annotation</h2>
 *
 * Forge's event bus on this version refuses {@code register(Object)} for a
 * class with a single listener in it, in those words: "You should directly call
 * addListener() on the EventBus of Post instead." Every event is now its own
 * bus, reached through a static field on the event type, and the reflective
 * scan is only there for classes that listen to several.
 */
public final class ClientTicks {

    private ClientTicks() {
    }

    /** Called once, from the mod's constructor. */
    public static void subscribe() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(ClientTicks::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // The title screen and not merely "some screen": the loading screen
            // is a screen too, and opening a world from there loses. Forge
            // finishes filling its frozen registries somewhere between the two,
            // and a world opened before that dies reading its own saved
            // registry snapshot — "Cannot invoke ForgeRegistry.getKeys()
            // because frozen is null", from inside the game's loader, with
            // nothing in it that names the mod that asked.
            if (mc.screen instanceof TitleScreen) {
                DevWorld.openIfAsked();
            }
            return;
        }
        // In a world, with the frame just finished: the one moment the picture
        // can be read back and compared against the same route without us.
        FrameProbe.endFrame();
    }
}
