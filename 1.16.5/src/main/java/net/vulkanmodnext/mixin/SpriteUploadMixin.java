package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.inventory.container.PlayerContainer;
import net.vulkanmodnext.client.AtlasAnimations;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every animation frame a block sprite uploads, seen on its way into OpenGL.
 *
 * The one private method both animation paths end in: the plain frame step and
 * the interpolated one (water and lava blend between frames and upload the
 * blend through here too). Taking it here rather than at the atlas means the
 * frame is known without asking the driver what was bound.
 */
@Mixin(TextureAtlasSprite.class)
public abstract class SpriteUploadMixin {

    @Shadow
    @Final
    protected NativeImage[] mainImage;

    @Shadow
    @Final
    private int x;

    @Shadow
    @Final
    private int y;

    @Inject(method = "upload(II[Lnet/minecraft/client/renderer/texture/NativeImage;)V",
            at = @At("HEAD"))
    private void vulkanmodnext$mirror(int frameX, int frameY, NativeImage[] frames,
                                     CallbackInfo ci) {
        TextureAtlasSprite self = (TextureAtlasSprite) (Object) this;
        if (!self.atlas().location().equals(PlayerContainer.BLOCK_ATLAS)) {
            return;
        }
        AtlasAnimations.record(frames, frameX, frameY, x, y, self.getWidth(), self.getHeight(),
                mainImage.length);
    }
}
