package net.vulkanmodnext.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The list of sections the game has decided are visible this frame.
 *
 * <p>It is the game's own answer, in the game's own order, and taking it rather
 * than working one out is deliberate: the first thing this port has to prove is
 * that it can draw what vanilla draws. A visibility search of our own is an
 * optimisation and comes later, with a measurement beside it.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccess {

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> vulkanmodnext$visibleSections();
}
