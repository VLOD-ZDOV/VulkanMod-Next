package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The state bundle behind a render type, which is where its texture is.
 *
 * Every render type the game makes with {@code create} is one of these private
 * {@code Type}s, and the texture a creature is drawn with is recorded nowhere
 * else: the render type binds it at draw time and the vertices carry only
 * coordinates into it. Anything that is not a {@code Type} is a mod's own class
 * and is left to the game — see {@code EntityGeometry}.
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$Type")
public interface RenderTypeAccess {

    @Accessor("state")
    RenderType.State vulkanmodnext$state();
}
