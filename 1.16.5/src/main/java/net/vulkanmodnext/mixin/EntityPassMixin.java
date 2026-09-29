package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.math.vector.Matrix4f;
import net.vulkanmodnext.client.EntityGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks where the game is drawing the world's creatures.
 *
 * Without it the model hook would also fire for a mob in an inventory slot, a
 * spawn egg preview or a mod's own screen — none of which are the world, and
 * all of which would be placed as though they were. What a part's vertices
 * are relative to is the camera, and there is no camera in a menu.
 *
 * <h2>Why the profiler's section names</h2>
 *
 * On 1.12.2 the entity pass is a method of its own, {@code renderEntities},
 * and its two ends are the obvious anchors. Here it is a stretch of the
 * middle of {@code renderLevel}, and the only thing that names its two ends
 * is the profiler: {@code "entities"} opens it, right after the three opaque
 * terrain layers, and {@code "blockentities"} closes it. They are string
 * constants the game itself relies on for its debug screen, which makes them
 * the steadiest thing in the method to hold on to.
 *
 * <p>Block entities are left out on purpose. Chests, signs and beds draw
 * through the same model class, but out of the block atlas's sprite sheets,
 * which reach the model wrapped and would be refused anyway; the window stays
 * as small as the thing it is for.
 */
@Mixin(WorldRenderer.class)
public abstract class EntityPassMixin {

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/profiler/IProfiler;popPush(Ljava/lang/String;)V",
            args = "ldc=entities"))
    private void vulkanmodnext$openEntityPass(MatrixStack matrices, float partialTicks,
                                             long limitTime, boolean outline,
                                             ActiveRenderInfo camera, GameRenderer renderer,
                                             LightTexture lightmap, Matrix4f projection,
                                             CallbackInfo ci) {
        // Here and not later: the pose stack holds the camera and nothing else
        // at this moment — the game checks it is empty right after the loop —
        // so inverting its top is inverting the view.
        EntityGeometry.beginPass(matrices);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/profiler/IProfiler;popPush(Ljava/lang/String;)V",
            args = "ldc=blockentities"))
    private void vulkanmodnext$closeEntityPass(MatrixStack matrices, float partialTicks,
                                              long limitTime, boolean outline,
                                              ActiveRenderInfo camera, GameRenderer renderer,
                                              LightTexture lightmap, Matrix4f projection,
                                              CallbackInfo ci) {
        EntityGeometry.endPass();
    }

    /**
     * And stood down again at the end of the frame, whatever happened between.
     *
     * The close above is reached on every normal frame. This is for the other
     * kind: a window left open would take the player drawn in the inventory
     * screen, place it with the world's camera, and cancel the game's own
     * drawing of it.
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void vulkanmodnext$standDown(MatrixStack matrices, float partialTicks,
                                        long limitTime, boolean outline,
                                        ActiveRenderInfo camera, GameRenderer renderer,
                                        LightTexture lightmap, Matrix4f projection,
                                        CallbackInfo ci) {
        EntityGeometry.standDown();
    }
}
