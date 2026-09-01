package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmodnext.client.TerrainFrame;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The top of the world pass, which is where the frame's matrices are.
 *
 * <h2>Why this method and not the one that draws</h2>
 *
 * Everything the terrain renderer needs about the camera arrives here as a
 * parameter and nowhere else: the model-view, the projection, and the fog
 * colour the game has already worked out for this frame. By the time the
 * sections are actually drawn the game is several layers deep inside a frame
 * graph, and the matrices have become bytes in a uniform buffer that nothing
 * reads back.
 *
 * <p>Nothing is drawn from here. It only remembers, which is why it is an
 * injection at the head with no cancel: if it never runs, the terrain hook
 * declines every group and the game renders exactly as it always did.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void vulkanmodnext$frame(GraphicsResourceAllocator resources, DeltaTracker delta,
                                     boolean renderBlockOutline, Camera camera,
                                     Matrix4f modelView, Matrix4f projection,
                                     Matrix4f cullingProjection, GpuBufferSlice fog,
                                     Vector4f fogColour, boolean skyVisible, CallbackInfo ci) {
        TerrainFrame.beginFrame(modelView, projection, fogColour);
    }
}
