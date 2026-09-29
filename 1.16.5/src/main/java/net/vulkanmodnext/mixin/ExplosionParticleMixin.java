package net.vulkanmodnext.mixin;

import net.minecraft.client.particle.HugeExplosionParticle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particles.IParticleData;
import net.vulkanmodnext.client.ExplosionParticles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a crater's worth of TNT from asking for a thousand puffs a tick.
 *
 * On 1.12.2 this sat on {@code Explosion.doExplosionB}, where the client asked
 * for a puff and a smoke at every destroyed block. On 1.16.5
 * {@code Explosion.finalizeExplosion} asks for one particle and no more; the
 * count now comes from the emitter that one particle is, which spawns six
 * large explosion puffs every tick for eight ticks. So the budget sits on the
 * emitter's request instead — see {@link ExplosionParticles}.
 *
 * The emitter itself is left alone. It is invisible and cheap, and dropping
 * one would take all 48 of its puffs with it, in one place — the hole the
 * one-in-eight thinning exists to avoid.
 *
 * No {@code isClientSide} guard, unlike on 1.12.2: this is a particle, and
 * particles only exist on the client.
 */
@Mixin(HugeExplosionParticle.class)
public abstract class ExplosionParticleMixin {

    @Redirect(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/world/ClientWorld;addParticle"
                            + "(Lnet/minecraft/particles/IParticleData;DDDDDD)V"))
    private void vulkanmodnext$budget(ClientWorld world, IParticleData type,
                                     double x, double y, double z,
                                     double xSpeed, double ySpeed, double zSpeed) {
        if (ExplosionParticles.allow()) {
            world.addParticle(type, x, y, z, xSpeed, ySpeed, zSpeed);
        }
    }
}
