package net.vulkanmodnext.mixin;

import net.minecraft.client.particle.IParticleRenderType;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Queue;

/**
 * The live particles, by render type, for the loop client/ParticleHooks runs
 * at the translucent layer. Read, never changed: ticking, spawning and
 * removal stay the game's.
 */
@Mixin(ParticleManager.class)
public interface ParticleManagerAccess {

    @Accessor("particles")
    Map<IParticleRenderType, Queue<Particle>> vulkanmodnext$particles();
}
