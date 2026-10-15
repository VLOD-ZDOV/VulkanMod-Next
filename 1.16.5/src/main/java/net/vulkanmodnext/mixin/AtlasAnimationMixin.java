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

    /**
     * A resource reload rebuilds the atlas in place: same texture object,
     * same OpenGL name. Anything keyed on the name never notices, and the
     * renderer went on drawing the previous pack's textures. Said here, after
     * the new sprites are in, so the next frame copies the atlas again.
     */
    @Inject(method = "reload", at = @At("RETURN"))
    private void vulkanmodnext$reloaded(AtlasTexture.SheetData sheet, CallbackInfo ci) {
        if (((AtlasTexture) (Object) this).location().equals(PlayerContainer.BLOCK_ATLAS)) {
            net.vulkanmodnext.client.TerrainFrame.atlasReloaded();
        }
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void vulkanmodnext$sendFrames(CallbackInfo ci) {
        if (((AtlasTexture) (Object) this).location().equals(PlayerContainer.BLOCK_ATLAS)) {
            AtlasAnimations.flush();
        }
    }
}
