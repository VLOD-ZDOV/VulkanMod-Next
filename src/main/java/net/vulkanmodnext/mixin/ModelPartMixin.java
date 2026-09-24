package net.vulkanmodnext.mixin;

import net.minecraft.client.model.ModelRenderer;
import net.vulkanmodnext.client.EntityCapture;
import net.vulkanmodnext.client.EntityGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches every model part the game draws, and takes the ones Vulkan draws.
 *
 * Both ends of the call, because the skeleton nests: a part's children are
 * drawn inside its frame, and without the second hook every sibling would be
 * placed inside its elder brother instead of beside him.
 *
 * Only the head is cancellable, and it cancels only when
 * {@code EntityGeometry.takePart} has taken the part — and with it every child
 * — into Vulkan. This is the busiest shared method in the client — every
 * creature, every armour layer, every mod that builds its models the ordinary
 * way passes through here — so whenever the part is not taken the hook only
 * looks, and a mod that does something unusual goes on drawing exactly as it
 * did. The return hook is skipped along with a cancelled call, which is
 * correct: the part was never begun on this side either.
 */
@Mixin(ModelRenderer.class)
public abstract class ModelPartMixin {

    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$capture(float scale, CallbackInfo ci) {
        // Taking the bone means taking its children: they are drawn inside the
        // frame this call builds, so a hook that stops it never sees them. The
        // walk is repeated on our side, and only then is the game told not to
        // bother.
        if (EntityGeometry.takePart((ModelRenderer) (Object) this, scale)) {
            ci.cancel();
            return;
        }
        EntityCapture.beginPart((ModelRenderer) (Object) this, scale);
    }

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void vulkanmodnext$leave(float scale, CallbackInfo ci) {
        EntityCapture.endPart();
    }
}
