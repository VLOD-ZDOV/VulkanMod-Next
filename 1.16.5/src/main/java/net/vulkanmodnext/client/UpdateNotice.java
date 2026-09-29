package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Says once, in chat, that {@link UpdateCheck} found a newer build.
 *
 * <h2>Why chat, and why not at the moment of joining</h2>
 *
 * The 1.12.2 build says it at the top of its settings screen, which is a
 * screen most players open once. Chat is where a player already reads what
 * the game has to tell them, and a line there can carry the link — clicking it
 * goes through the game's own open-link confirmation, which shows the address
 * before anything opens.
 *
 * Not on the login event: at that moment the terrain loading screen is still
 * up, and a chat line fades on a timer that starts when it is added, so it
 * would have faded behind a screen nobody could see past. It waits for the
 * first tick with a world and no screen, which is the first moment the line
 * can actually be read.
 *
 * <h2>Once per session</h2>
 *
 * Not once per world: someone who hops between worlds has been told, and a
 * notice repeated on every join is a notice people learn to skip.
 */
public final class UpdateNotice {

    private boolean told;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (told || event.phase != TickEvent.Phase.END) {
            return;
        }
        String newer = UpdateCheck.newerVersion();
        if (newer == null) {
            // Checked every tick until the answer arrives or the session ends;
            // one volatile read, and a check that found nothing never gets
            // further than this line.
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        told = true;
        String page = UpdateCheck.downloadPage();
        mc.gui.getChat().addMessage(new StringTextComponent("VulkanMod Next " + newer
                + " is available: ")
                .withStyle(TextFormatting.GOLD)
                .append(new StringTextComponent(page).withStyle(style -> style
                        .withColor(TextFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, page)))));
    }
}
