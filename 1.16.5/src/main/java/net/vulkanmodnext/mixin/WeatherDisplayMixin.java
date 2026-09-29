package net.vulkanmodnext.mixin;

import net.minecraft.world.World;
import net.vulkanmodnext.client.WorldDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Changes what the world says the weather is, on this client.
 *
 * {@code isRaining} and {@code isThundering} are both derived from the two
 * levels, and so are the rain drawn, the sky darkening and the rain the
 * terrain shader is sent, so these two are the whole of it.
 *
 * <h2>Why this cannot reach the server</h2>
 *
 * {@code isClientSide} is false on the world the integrated server ticks and
 * true on the one this client draws — they are different objects of the same
 * class. Every method here returns immediately on the server's copy, so what
 * is changed is what this screen is told and nothing else. A storm the server
 * believes in still charges a creeper.
 *
 * The time of day is in {@link WorldTimeDisplayMixin}: on this version it is
 * not a method of the world at all.
 */
@Mixin(World.class)
public abstract class WeatherDisplayMixin {

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$rain(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!((World) (Object) this).isClientSide || !WorldDisplay.overridingWeather()) {
            return;
        }
        cir.setReturnValue(Float.valueOf(WorldDisplay.rain(0.0f)));
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$thunder(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!((World) (Object) this).isClientSide || !WorldDisplay.overridingWeather()) {
            return;
        }
        cir.setReturnValue(Float.valueOf(WorldDisplay.thunder(0.0f)));
    }
}
