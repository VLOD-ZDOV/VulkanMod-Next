package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.client.SunSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Puts a different picture on the sun and the moon, and touches nothing else.
 *
 * The sky is drawn by one long vanilla method, {@code WorldRenderer.renderSky},
 * which binds exactly two textures on the overworld path — the sun, then the
 * moon sheet. Redirecting the bind and answering only for those two is the
 * narrowest possible change: the quads, their sizes, their positions, the
 * blend, the order and the phase are all still vanilla's.
 *
 * A dimension with a Forge {@code ISkyRenderHandler} returns before either
 * bind, and the End sky lives in a method of its own, so neither ever reaches
 * this — which is right: a mod that draws its own sky has chosen its own sun.
 *
 * The 1.12.2 build asks a shader pack's own sun and moon first; that part
 * ({@code skinPack}) is not ported yet, so here it is the drawn disc or
 * vanilla's.
 */
@Mixin(WorldRenderer.class)
public abstract class SkySunMixin {

    private static final ResourceLocation SUN =
            new ResourceLocation("textures/environment/sun.png");
    private static final ResourceLocation MOON =
            new ResourceLocation("textures/environment/moon_phases.png");

    @Redirect(method = "renderSky(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureManager;"
                            + "bind(Lnet/minecraft/util/ResourceLocation;)V"))
    private void vulkanmodnext$bindSky(TextureManager manager, ResourceLocation texture) {
        ResourceLocation ours = null;
        if (SUN.equals(texture)) {
            ours = SunSkin.current();
        } else if (MOON.equals(texture)) {
            ours = SunSkin.currentMoon();
        }
        manager.bind(ours != null ? ours : texture);
    }
}
