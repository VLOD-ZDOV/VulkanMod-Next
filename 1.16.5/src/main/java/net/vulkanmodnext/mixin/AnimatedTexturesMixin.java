package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.inventory.container.PlayerContainer;
import net.vulkanmodnext.client.AnimatedSprites;
import net.vulkanmodnext.client.AtlasAnimations;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Lets animated textures be turned off, or stepped only where they are seen.
 *
 * Every animated sprite in an atlas uploads a new frame to the GPU each tick,
 * whether or not a single one of its blocks is on screen. Vanilla has no
 * switch for it, and with a modpack's worth of machines and fluids the uploads
 * add up to real frame time. With animations off this is the blunt version:
 * off means off, everywhere. With smart animations on, the block atlas steps
 * only the sprites something on screen is using ({@link AnimatedSprites}).
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

    @Shadow
    @Final
    private List<TextureAtlasSprite> animatedTextures;

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$skipAnimations(CallbackInfo ci) {
        if (!VulkanConfig.on("animatedTextures")) {
            ci.cancel();
            return;
        }
        AtlasTexture self = (AtlasTexture) (Object) this;
        if (!self.location().equals(PlayerContainer.BLOCK_ATLAS)) {
            return;
        }
        // Numbered whether or not the setting is on. A chunk can only record
        // its sprites once they are numbered, and a single visible chunk
        // without a record makes every sprite step. Numbering on the first
        // tick after the switch would leave the chunks the switch's own
        // rebuild finished in that tick without one, and the setting would
        // then save nothing until the next rebuild, with nothing to say so.
        AnimatedSprites.index(this.animatedTextures);
        if (!VulkanConfig.on("smartAnimations")) {
            return;
        }
        // Vanilla's own body over the wanted sprites: the bind is its first
        // line and has to be kept, because every sprite uploads into whatever
        // atlas is bound.
        self.bind();
        AnimatedSprites.stepWanted(this.animatedTextures);
        // Cancelling takes the return hook with it, and that hook is what hands
        // the frames to the Vulkan copy of the atlas. Without this line the
        // blocks drawn through Vulkan would stand still while the same blocks
        // in the hand moved — the shape of bug nobody would connect to a
        // setting called Smart Animations, and the one 1.12.2 wrote down.
        AtlasAnimations.flush();
        ci.cancel();
    }
}
