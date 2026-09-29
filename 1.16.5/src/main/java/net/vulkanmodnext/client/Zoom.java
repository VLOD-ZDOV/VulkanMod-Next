package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;

/**
 * Hold-to-zoom, the way OptiFine does it: press and hold the key and the field
 * of view narrows; let go and it returns.
 *
 * Two details separate this from simply lowering the FOV.
 *
 * Mouse sensitivity is scaled by the same factor, because at a quarter of the
 * field of view an unchanged sensitivity makes aiming impossible — the view
 * sweeps four times as far across the screen for the same hand movement. The
 * original is restored as soon as the zoom is fully out, and the game is never
 * asked to save options while a temporary value is in place.
 *
 * The transition is eased off the wall clock rather than snapped. Zoom state
 * is decided on the 20 Hz tick, and interpolating anything on that clock is
 * precisely what makes a zoom look stepped; timing it in real seconds also
 * keeps the transition the same length at 30 frames a second and at 300.
 *
 * The key is registered through Forge, so it appears in Controls and can be
 * rebound like any other. It defaults to C, as on 1.12.2 and in OptiFine; in
 * creative mode vanilla also puts "save hotbar" on C, and Controls shows the
 * clash in red rather than either one silently losing.
 */
public final class Zoom {

    private static final KeyBinding KEY = new KeyBinding(
            "key.vulkanmodnext.zoom", GLFW.GLFW_KEY_C, "key.categories.vulkanmodnext");
    /** Long enough to read as a movement, short enough not to feel sluggish. */
    private static final float ZOOM_SECONDS = 0.12f;

    private static boolean active;
    private static boolean overriding;
    private static double savedSensitivity;
    /** 0 = no zoom, 1 = fully zoomed. Advanced per frame. */
    private static float progress;
    private static long lastFrameNanos;

    private Zoom() {
    }

    /** From client setup: Forge takes key bindings then and not before. */
    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    private static void beginOverride(Minecraft mc) {
        if (!overriding) {
            savedSensitivity = mc.options.sensitivity;
            overriding = true;
        }
    }

    private static void endOverride(Minecraft mc) {
        if (overriding) {
            mc.options.sensitivity = savedSensitivity;
            overriding = false;
        }
    }

    public static final class Handler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            // No zooming out of a menu, a loading screen or an unfocused
            // window: in all three the key would read as stuck down, and the
            // clamped sensitivity would outlive the zoom. Losing focus is the
            // one that matters, because alt-tabbing away and quitting from
            // there is how a temporary value would end up saved for good.
            active = VulkanConfig.on("zoom")
                    && mc.level != null
                    && mc.screen == null
                    && mc.isWindowActive()
                    && KEY.isDown();
            if (mc.level == null) {
                // Back at the main menu the FOV event stops firing, so the
                // ease below would never finish putting sensitivity back.
                active = false;
                progress = 0.0f;
                endOverride(mc);
            }
        }

        /**
         * Fired from {@code GameRenderer.getFov}, twice a frame: once for the
         * world and once for the hand, which is drawn at a fixed 70 degrees
         * and narrowed by the same divisor — as on 1.12.2, where the same
         * event is fired for both. The second call of a frame finds almost no
         * time elapsed, so it moves the ease by nothing and only repeats the
         * divisor.
         */
        @SubscribeEvent
        public void onFov(EntityViewRenderEvent.FOVModifier event) {
            Minecraft mc = Minecraft.getInstance();
            long now = System.nanoTime();
            float elapsed = lastFrameNanos == 0 ? 0.0f : (now - lastFrameNanos) / 1.0e9f;
            lastFrameNanos = now;
            // A long stall — world load, alt-tab — must not teleport the zoom.
            if (elapsed > 0.25f) {
                elapsed = 0.25f;
            }

            float target = active ? 1.0f : 0.0f;
            float perSecond = elapsed / ZOOM_SECONDS;
            if (progress < target) {
                progress = Math.min(target, progress + perSecond);
            } else if (progress > target) {
                progress = Math.max(target, progress - perSecond);
                // Widening the view has to invalidate the visible-chunk list.
                // WorldRenderer.setupRender rebuilds it only when the camera
                // moves or turns, or when needsUpdate is set — the field of
                // view is not in that condition at all — so zooming in,
                // letting it rebuild against the narrow frustum, then zooming
                // out would leave the world drawn as the narrow cone it was
                // during the zoom until something else made the player turn.
                // Narrowing needs no such call: the existing list is then a
                // superset of what is visible.
                if (mc.levelRenderer != null) {
                    mc.levelRenderer.needsUpdate();
                }
            }

            if (progress <= 0.0f) {
                endOverride(mc);
                return;
            }
            beginOverride(mc);
            // Interpolating the divisor rather than the angle keeps the
            // apparent speed even; sweeping linearly from 70 to 17.5 degrees
            // rushes at the start and crawls at the end.
            float divisor = 1.0f + (VulkanConfig.get("zoomFactor") - 1.0f) * smoothstep(progress);
            mc.options.sensitivity = savedSensitivity / divisor;
            event.setFOV(event.getFOV() / divisor);
        }

        private static float smoothstep(float t) {
            return t * t * (3.0f - 2.0f * t);
        }
    }
}
