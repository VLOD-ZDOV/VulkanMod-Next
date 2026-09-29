package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmodnext.client.TerrainFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one moment in the frame when everything in the world has been drawn.
 *
 * The terrain is finished and glued into the game's frame before the game has
 * drawn a single entity, which is too early for anything that works over the
 * whole picture: a glow would be over a world with no creatures, no particles
 * and no weather in it. The profiler names the moment it is all there — the
 * section called "hand" opens once the world is finished and before the arm
 * is drawn over it. The same string the 1.12.2 twin injects on.
 *
 * The glow, the ambient occlusion and the tone pass all ran on 1.16.5's Vulkan
 * side and nothing ever called them; this is the call.
 */
@Mixin(GameRenderer.class)
public abstract class SceneEffectsMixin {

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/profiler/IProfiler;popPush(Ljava/lang/String;)V",
                    args = "ldc=hand"))
    private void vulkanmodnext$sceneEffects(float partialTicks, long finishNanos,
                                           MatrixStack matrices, CallbackInfo ci) {
        TerrainFrame.applySceneEffects();
    }
}
