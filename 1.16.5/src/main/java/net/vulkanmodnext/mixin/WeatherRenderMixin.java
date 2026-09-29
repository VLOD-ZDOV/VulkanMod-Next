package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.vulkanmodnext.client.WeatherHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends rain and snow to Vulkan instead of to OpenGL.
 *
 * Two redirects, as on 1.12.2: what the weather looks like is decided by
 * column walking, biome temperature and seeded randomness that this mod has no
 * business owning, and what it is drawn <em>by</em> is two calls. The third
 * hook is new to this version, because the method now runs twice a frame —
 * once early, for Vulkan, from client/WeatherHooks — and the game's own call
 * has to stand down when the early one took the weather.
 *
 * <p>A dimension with its own weather renderer leaves the method before any of
 * the redirected calls, and WeatherHooks never makes the early call for it.
 */
@Mixin(WorldRenderer.class)
public abstract class WeatherRenderMixin {

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$alreadyDrawn(LightTexture lightmap, float partialTicks,
                                           double viewX, double viewY, double viewZ,
                                           CallbackInfo ci) {
        if (WeatherHooks.skipGameCall()) {
            ci.cancel();
        }
    }

    @Redirect(method = "renderSnowAndRain",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureManager;"
                            + "bind(Lnet/minecraft/util/ResourceLocation;)V"))
    private void vulkanmodnext$noteSheet(TextureManager textures, ResourceLocation location) {
        // Still bound: a batch the renderer refuses is drawn by the game.
        textures.bind(location);
        WeatherHooks.noteTexture(location);
    }

    @Redirect(method = "renderSnowAndRain",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;end()V"))
    private void vulkanmodnext$drawInVulkan(Tessellator tessellator) {
        WeatherHooks.end(tessellator);
    }
}
