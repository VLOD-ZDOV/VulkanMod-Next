package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderState;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The texture half of a render type's state; see {@link RenderTypeAccess}. */
@Mixin(RenderType.State.class)
public interface RenderTypeStateAccess {

    @Accessor("textureState")
    RenderState.TextureState vulkanmodnext$textureState();
}
