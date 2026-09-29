package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.texture.AtlasTexture;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets animated textures be turned off.
 *
 * Every animated sprite in an atlas uploads a new frame to the GPU each tick,
 * whether or not a single one of its blocks is on screen. Vanilla has no
 * switch for it, and with a modpack's worth of machines and fluids the uploads
 * add up to real frame time. This is the blunt version: off means off,
 * everywhere.
 *
 * On 1.12.2 this was {@code TextureMap.updateAnimations}. Here each atlas is a
 * tickable texture: {@code TextureManager.tick} calls {@code AtlasTexture.tick},
 * which calls this — directly, or through a recorded render call when it is not
 * on the render thread — and this binds the atlas and steps every animated
 * sprite in it. Stopping it here catches both routes. It also catches every
 * atlas rather than the block atlas alone: the particle sheet animates the
 * same way, and 1.12.2 had only the one atlas for it to mean.
 *
 * The sprites stand on whichever frame they had reached, not on their first:
 * nothing uploads, so nothing moves back.
 */
@Mixin(AtlasTexture.class)
public abstract class AnimatedTexturesMixin {

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$skipAnimations(CallbackInfo ci) {
        if (!VulkanConfig.on("animatedTextures")) {
            ci.cancel();
        }
    }
}
