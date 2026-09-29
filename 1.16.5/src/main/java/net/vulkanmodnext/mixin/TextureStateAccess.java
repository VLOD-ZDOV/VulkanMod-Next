package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderState;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * Which picture a texture state binds. Empty for the states that bind none —
 * lines, the world border — which are never a creature's skin.
 */
@Mixin(RenderState.TextureState.class)
public interface TextureStateAccess {

    @Accessor("texture")
    Optional<ResourceLocation> vulkanmodnext$texture();
}
