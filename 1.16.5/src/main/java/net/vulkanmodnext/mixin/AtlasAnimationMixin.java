package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.inventory.container.PlayerContainer;
import net.vulkanmodnext.client.AtlasAnimations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of an atlas's animation tick: the frames it uploaded go to Vulkan
 * together. See {@link AtlasAnimations}.
 */
@Mixin(AtlasTexture.class)
public abstract class AtlasAnimationMixin {

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void vulkanmodnext$sendFrames(CallbackInfo ci) {
        if (((AtlasTexture) (Object) this).location().equals(PlayerContainer.BLOCK_ATLAS)) {
            AtlasAnimations.flush();
        }
    }
}
