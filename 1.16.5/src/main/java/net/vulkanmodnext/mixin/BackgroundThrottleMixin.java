package net.vulkanmodnext.mixin;

import net.minecraft.client.Minecraft;
import net.vulkanmodnext.client.BackgroundThrottle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where the game asks what its frame cap is, once a frame, in {@code runTick}.
 *
 * The game sleeps only when the answer is below the "unlimited" top of its
 * slider, 260. The background cap tops out at 60, so a capped answer is always
 * slept on — even with the player's own limit at unlimited, which is the case
 * this exists for — and an uncapped one is passed through untouched. See
 * {@link BackgroundThrottle} for why the game's limiter and not one of ours.
 */
@Mixin(Minecraft.class)
public abstract class BackgroundThrottleMixin {

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void vulkanmodnext$background(CallbackInfoReturnable<Integer> cir) {
        int vanilla = cir.getReturnValueI();
        int capped = BackgroundThrottle.limit(vanilla);
        if (capped != vanilla) {
            cir.setReturnValue(Integer.valueOf(capped));
        }
    }
}
