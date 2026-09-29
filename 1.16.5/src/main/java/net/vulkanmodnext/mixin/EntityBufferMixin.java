package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmodnext.client.EntityGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Which render type each vertex builder was handed out for.
 *
 * <h2>Why a creature's skin has to be learned here</h2>
 *
 * On 1.12.2 the skin was whatever texture had just been bound, and a mirror of
 * the bind call was enough. On this version nothing is bound while a model is
 * drawn: the renderer asks this buffer source for a builder for a render type,
 * the model writes vertices into that builder, and the texture is bound only
 * when the batch is flushed, long after. The builder is the one thing the
 * model part is given — so the builder is what has to say which render type,
 * and therefore which texture, it belongs to.
 *
 * <p>The answer is exact rather than a guess. The shared builder is re-begun
 * for a new render type on every change, so the last type it was handed out
 * for is the type whose vertices it is collecting now; a fixed builder only
 * ever has one. A builder the game wraps on its way to the model — the glint
 * of enchanted armour, the outline of a glowing mob, a sprite sheet's UV remap
 * — is a different object and is never found here, and a part given one is
 * left to the game. That is the whole of the fallback for those three.
 */
@Mixin(IRenderTypeBuffer.Impl.class)
public abstract class EntityBufferMixin {

    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void vulkanmodnext$note(RenderType type, CallbackInfoReturnable<IVertexBuilder> cir) {
        if (!EntityGeometry.watching()) {
            return;
        }
        EntityGeometry.noteBuffer((IRenderTypeBuffer.Impl) (Object) this, type,
                cir.getReturnValue());
    }
}
