package net.vulkanmodnext.mixin;

import net.minecraft.client.particle.IParticleRenderType;
import net.minecraft.client.particle.ParticleManager;
import net.vulkanmodnext.client.ParticleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * Skips, in the game's own particle loop, the types already drawn by Vulkan
 * this frame.
 *
 * Per type rather than the whole method, which is where the 1.12.2 build
 * differs: there every queue was a batch of quads from one of two sheets, and
 * here {@code CUSTOM} and any type a mod adds are drawn by code of their own
 * that this mod does not take. Those still need the loop.
 *
 * <p>The redirect is on the queue lookup because vanilla already treats a
 * missing queue as "nothing to draw" — the type's {@code begin} and {@code end}
 * sit inside that same test, so nothing about a skipped type is touched, not
 * even its texture bind. Forge's {@code renderParticles}, with the frustum, is
 * the one the world renderer calls; the game's own {@code render} only
 * forwards to it.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleRenderMixin {

    @Redirect(method = "renderParticles(Lcom/mojang/blaze3d/matrix/MatrixStack;"
            + "Lnet/minecraft/client/renderer/IRenderTypeBuffer$Impl;"
            + "Lnet/minecraft/client/renderer/LightTexture;"
            + "Lnet/minecraft/client/renderer/ActiveRenderInfo;F"
            + "Lnet/minecraft/client/renderer/culling/ClippingHelper;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
            // Forge's method, not the game's: it carries the same name at run
            // time as here and has no obfuscated one to look up. The call it
            // targets is the JDK's, so nothing in this hook needs remapping.
            remap = false)
    private Object vulkanmodnext$skipTaken(Map<?, ?> particles, Object type) {
        if (type instanceof IParticleRenderType && ParticleHooks.isTaken((IParticleRenderType) type)) {
            return null;
        }
        return particles.get(type);
    }
}
