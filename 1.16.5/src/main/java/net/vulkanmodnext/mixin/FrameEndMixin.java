package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmodnext.client.Flight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of a drawn frame, which is the only place a screenshot can be taken.
 *
 * <p>A tick knows which frame the route wants and cannot save one: at tick time
 * the picture belongs to the frame before it, and half of it may not have been
 * drawn yet. So the tick leaves a name and this acts on it.
 *
 * <p>At the return of {@code render} rather than of {@code runTick}: by here
 * the world, the entities and the interface have all been drawn into the main
 * target, and that target is what the screenshot reads. It is also before the
 * frame is handed to the window, which is why the picture saved is the picture
 * just drawn rather than the one before it.
 */
@Mixin(GameRenderer.class)
public abstract class FrameEndMixin {

    @Inject(method = "render", at = @At("RETURN"))
    private void vulkanmodnext$frameEnd(float partialTicks, long nanoTime, boolean renderLevel,
                                        CallbackInfo ci) {
        Flight.onFrameEnd();
    }
}
