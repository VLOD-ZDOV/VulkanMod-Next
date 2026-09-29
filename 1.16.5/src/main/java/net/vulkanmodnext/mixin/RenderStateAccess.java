package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.RenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A render type's name, which is the only thing that says what kind it is.
 *
 * Two render types with the same texture can want opposite things — one
 * blended and one not, one tested against depth and one only on equal depth —
 * and the name is where the game keeps that apart. Read once per render type
 * and cached; see {@code EntityGeometry.kindOf}.
 */
@Mixin(RenderState.class)
public interface RenderStateAccess {

    @Accessor("name")
    String vulkanmodnext$name();
}
