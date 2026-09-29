package net.vulkanmodnext.mixin;

import net.minecraft.client.particle.Particle;
import net.vulkanmodnext.client.DynamicLights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets dynamic light reach particles.
 *
 * Breaking a block underground with a torch in hand produced a cloud of
 * completely black specks against lit stone: the terrain shader had the light
 * and the particle did not. A particle asks for its own light map coordinate
 * rather than borrowing an entity's, so it needs its own hook. The subclasses
 * that override {@code getLightColor} either start from this one's answer
 * (digging, flame, lava, portal) or return full brightness outright, so they
 * are covered through it or need nothing.
 */
@Mixin(Particle.class)
public abstract class ParticleLightMixin {

    @Shadow
    protected double x;
    @Shadow
    protected double y;
    @Shadow
    protected double z;

    @Inject(method = "getLightColor", at = @At("RETURN"), cancellable = true)
    private void vulkanmodnext$addDynamicLight(float partialTicks,
                                               CallbackInfoReturnable<Integer> cir) {
        int packed = cir.getReturnValueI();
        int raised = DynamicLights.applyTo(packed, this.x, this.y, this.z);
        if (raised != packed) {
            cir.setReturnValue(raised);
        }
    }
}
