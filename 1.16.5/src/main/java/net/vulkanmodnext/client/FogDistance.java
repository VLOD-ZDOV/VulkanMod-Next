package net.vulkanmodnext.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.potion.Effects;
import net.minecraft.tags.FluidTags;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;

/**
 * Pushes the game's distance fog further out, or pulls it in.
 *
 * Written after reading how Celeritas Extra does it (LGPL-3.0,
 * github.com/Sumire-Labs/Celeritas-Extra), which took the idea in turn from
 * Sodium Extra. Not a copy of their code — the arithmetic is two lines and the
 * plumbing is ours — but the rule below is theirs and is the whole reason this
 * is worth doing carefully rather than quickly.
 *
 * <h2>The rule</h2>
 *
 * Some of this game's fog is scenery and some of it is information. Blindness,
 * being under water, being in lava: each of those is told to the player almost
 * entirely by fog, and a setting that thins the haze on a mountain range must
 * not also tell somebody they can see through lava. So the scale is applied to
 * the fog of an ordinary view and to nothing else.
 *
 * <h2>Why a Forge event and not a mixin</h2>
 *
 * On 1.12.2 this was an injection at the return of {@code setupFog}. Here Forge
 * already fires {@code RenderFogEvent} at the end of {@code FogRenderer.setupFog},
 * after the game has set the start, the end and the mode — and only on the
 * linear branch. Under water the game takes the exponential branch and the
 * event never fires, so the first of the three cases above is excluded by the
 * game itself; lava and blindness are linear here and are asked for below.
 *
 * <h2>Why this is a change to OpenGL and not to this renderer</h2>
 *
 * The terrain shader does not decide its own fog. {@link TerrainFrame} reads
 * the game's — start, end and density, out of GL, once a frame — precisely so
 * that the blocks cannot disagree with the sky, the creatures and everything
 * else the game draws around them. So changing it here changes it everywhere,
 * in one place, and this renderer follows without knowing anything happened.
 *
 * <h2>Why only linear fog</h2>
 *
 * Vanilla uses linear fog for distance and exponential fog for water. Scaling a
 * density would be a different sum with a different meaning, and the only fog
 * that wants scaling is the one measured in blocks.
 */
public final class FogDistance {

    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.RenderFogEvent event) {
        int percent = VulkanConfig.get("fogDistance");
        if (percent == 100 || tellsYouSomething(event)) {
            return;
        }
        // The event is only posted from the linear branch today. Asked anyway,
        // because a mod that answers FogDensity changes the mode without this
        // event knowing, and the guard is one query.
        if (GL11C.glGetInteger(GL11.GL_FOG_MODE) != GL11.GL_LINEAR) {
            return;
        }
        float scale = percent / 100.0f;
        // Through the game's own state tracker, never straight at GL.
        //
        // It remembers what it last set and skips the call when the value has
        // not moved. Writing behind it leaves the two disagreeing: the game
        // believes the fog still starts where it put it, so next frame it sets
        // the same number, sees no change and sends nothing — and this scaling
        // lands on its own previous answer instead of on the game's. Three
        // becomes nine becomes twenty-seven, once a frame, until the numbers
        // stop being numbers, and the terrain shader reading them out of GL
        // fogs the entire world away. Told properly, the tracker sees a value
        // it did not write, restores the game's next frame, and this scales it
        // once more from the same place every time.
        //
        // Both ends, so the band the fog thickens across keeps its shape rather
        // than stretching from a fixed start — pushing only the far end makes a
        // long soft smear instead of a wall of haze further away.
        //
        // Read back from GL rather than from the event: the event carries the
        // far end only, and the tracker has just written both to GL, so what
        // GL says here is exactly what the game chose.
        RenderSystem.fogStart(GL11C.glGetFloat(GL11.GL_FOG_START) * scale);
        RenderSystem.fogEnd(GL11C.glGetFloat(GL11.GL_FOG_END) * scale);
    }

    /**
     * Whether the fog on screen is carrying gameplay state rather than distance.
     *
     * Boss fog is linear like an ordinary view's and is set up by the same
     * method, so the fog mode alone cannot tell it apart; it is asked for by
     * name, the same way the game decides to draw it. Lava is read from the
     * camera rather than the entity, because the camera is what the game itself
     * asks when it picks lava's fog — in third person the two differ.
     */
    private static boolean tellsYouSomething(EntityViewRenderEvent.RenderFogEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.getBossOverlay().shouldCreateWorldFog()) {
            return true;
        }
        if (event.getInfo().getFluidInCamera().is(FluidTags.LAVA)) {
            return true;
        }
        Entity view = event.getInfo().getEntity();
        return view instanceof LivingEntity
                && ((LivingEntity) view).hasEffect(Effects.BLINDNESS);
    }
}
