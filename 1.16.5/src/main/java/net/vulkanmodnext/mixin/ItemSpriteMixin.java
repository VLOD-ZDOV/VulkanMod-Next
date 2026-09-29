package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.item.ItemStack;
import net.vulkanmodnext.client.AnimatedSprites;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps an animated texture moving while it is drawn as an item.
 *
 * No chunk vouches for a lava bucket in the hand, a magma block in the
 * inventory or a sea lantern in an item frame, so smart animations would leave
 * them standing on whatever frame they had. Every baked item model is drawn
 * through this one method — the plain path calls it, and Forge's layered path
 * calls it once per layer — so marking the model here covers the hand, the
 * menus, a dropped stack and a frame at once.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemSpriteMixin {

    @Inject(method = "renderModelLists", at = @At("HEAD"))
    private void vulkanmodnext$markItemSprites(IBakedModel model, ItemStack stack, int light,
                                              int overlay, MatrixStack matrices,
                                              IVertexBuilder builder, CallbackInfo ci) {
        if (VulkanConfig.on("smartAnimations")) {
            AnimatedSprites.recordItemModel(model);
        }
    }
}
