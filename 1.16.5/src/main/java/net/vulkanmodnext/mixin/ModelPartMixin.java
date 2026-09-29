package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.model.ModelRenderer;
import net.vulkanmodnext.client.EntityCapture;
import net.vulkanmodnext.client.EntityGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches every model part the game draws, and takes the ones Vulkan draws.
 *
 * The equivalent of 1.12.2's hook on {@code ModelRenderer.render(float)}, on the
 * method that replaced it. This is the busiest shared method in the entity
 * pass — every creature, every armour layer, every mod that builds its models
 * the ordinary way passes through here — so it cancels only when
 * {@code EntityGeometry.take} has put the whole part, children included, into
 * a Vulkan batch. In every other case, and always outside the game's own
 * entity pass, it only looks, and whatever the part was going to do it does.
 *
 * One call here is also one bone: the game walks a skeleton by calling this
 * method on each child from inside its parent, so the take happens on the
 * outermost call and the calls it makes itself — into our builder — pass
 * straight through.
 */
@Mixin(ModelRenderer.class)
public abstract class ModelPartMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;Lcom/mojang/blaze3d/vertex/IVertexBuilder;IIFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$take(MatrixStack matrices, IVertexBuilder builder, int light,
                                    int overlay, float red, float green, float blue, float alpha,
                                    CallbackInfo ci) {
        // One static read on the path that runs outside the entity pass: the
        // inventory, a spawn-egg preview and the first-person arm all come
        // through here too, and none of them is the world.
        if (!EntityGeometry.watching()) {
            return;
        }
        ModelRenderer self = (ModelRenderer) (Object) this;
        if (EntityGeometry.take(self, matrices, builder, light, overlay, red, green, blue, alpha)) {
            ci.cancel();
            return;
        }
        EntityCapture.observe(self, builder);
    }
}
