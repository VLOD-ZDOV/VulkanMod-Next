package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.vector.Vector3d;
import net.vulkanmodnext.client.DynamicLights;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets dynamic light fall on entities and on the item in the player's hand,
 * not only on the ground under them.
 *
 * The terrain shader adds carried light while it shades a block face, which
 * reaches everything this renderer draws and nothing it does not — so a mob
 * standing in the pool of light from a dropped torch stayed as dark as if the
 * torch were not there, with the lit ground visible around its feet.
 *
 * Entities are drawn by the game from a single light map coordinate each, and
 * {@code getPackedLightCoords} is where it is made: block and sky light at the
 * entity's eye, packed as {@code block << 4 | sky << 20}. Raising the block
 * half of that answer is the whole of it — the entity is then drawn from the
 * same warm row of the light map the ground beneath it is using, by the game's
 * own renderer, with nothing else changed.
 *
 * <h2>One hook where 1.12.2 needed two</h2>
 *
 * On 1.12.2 the first-person item renderer read the world at the player's eye
 * by itself and needed a mixin of its own ({@code HeldItemLightMixin}), which
 * then failed silently for a release because it aimed at the wrong owner of the
 * call. On this version {@code GameRenderer.renderItemInHand} asks
 * {@code EntityRendererManager.getPackedLightCoords(player, partialTicks)},
 * which lands here — so the torch in the hand is lit by the same line that
 * lights the player.
 *
 * Here and not on the manager: this is the final method every renderer goes
 * through, and the manager only forwards to it.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityLightMixin {

    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private void vulkanmodnext$addDynamicLight(Entity entity, float partialTicks,
                                               CallbackInfoReturnable<Integer> cir) {
        if (!VulkanConfig.on("dynamicLights") || DynamicLights.count() == 0) {
            return;
        }
        int packed = cir.getReturnValueI();
        // The point vanilla itself just lit the entity at, interpolated to this
        // frame, so the raise and the vanilla answer are for the same place.
        Vector3d at = entity.getLightProbePosition(partialTicks);
        int raised = DynamicLights.applyTo(packed, at.x, at.y, at.z);
        DynamicLights.recordEntityLight(packed, raised);
        if (raised != packed) {
            cir.setReturnValue(raised);
        }
    }
}
