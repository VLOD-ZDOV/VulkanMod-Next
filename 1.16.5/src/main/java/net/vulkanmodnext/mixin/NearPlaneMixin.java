package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.math.vector.Matrix4f;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Moves the near clipping plane out, which is the only cure for depth fighting
 * at long render distances.
 *
 * Vanilla builds its projection with a near plane of 0.05 blocks:
 *
 * <pre>
 * Matrix4f.perspective(fov, aspect, 0.05F, this.renderDistance * 4.0F)
 * </pre>
 *
 * The depth buffer is 24-bit and its precision falls off with the square of the
 * distance divided by the near plane, so the gap it can still resolve at a
 * distance z is roughly {@code z² / (near · 2²⁴)}. Three hundred blocks out with
 * near at 0.05 that is about 0.107 blocks — and a snow layer sits 0.125 blocks
 * above the block it covers, whose top face is still drawn because a one-deep
 * snow layer is not an opaque cube. Two surfaces a hair further apart than the
 * depth buffer can tell: the speckled grey and sand showing through white snow
 * seen from any high vantage point.
 *
 * <h2>One place instead of three</h2>
 *
 * On 1.12.2 the constant was repeated in the camera setup, twice in the world
 * pass and again in the clouds' restore, and missing the last one silently
 * undid the setting below cloud height. Here every projection comes out of
 * {@code getProjectionMatrix}: the world, the sky and the clouds are all drawn
 * with the matrix {@code renderLevel} gets from it, so one constant moves all
 * of them together and they keep sharing a depth buffer.
 *
 * <h2>Not the hand</h2>
 *
 * The held item asks the same method for its own projection, with the FOV
 * setting turned off. That one is left at vanilla's value, as it was on 1.12.2
 * where the hand had its own call: the hand is drawn after the depth buffer is
 * cleared, so its near plane buys no precision, and the item sits close enough
 * to the eye that a larger one could cut into it.
 *
 * The Vulkan pass needs nothing extra: it reads the near plane back out of the
 * matrix the game hands it, so it follows whatever this returns.
 *
 * The price: geometry closer to the eye than the near plane is clipped away.
 * Pressed against a wall, or with the head inside a block, a larger value can
 * open a hole into it — which is why the setting still goes back to vanilla.
 */
@Mixin(GameRenderer.class)
public abstract class NearPlaneMixin {

    /**
     * Whether the projection being built is the world's. Set at the head of
     * the call and read by the constant a few instructions later, on the same
     * thread, because the constant handler cannot see the method's arguments.
     */
    @Unique
    private boolean vulkanmodnext$worldProjection;

    @Inject(method = "getProjectionMatrix", at = @At("HEAD"))
    private void vulkanmodnext$whichProjection(ActiveRenderInfo camera, float partialTicks,
                                               boolean useFovSetting,
                                               CallbackInfoReturnable<Matrix4f> cir) {
        this.vulkanmodnext$worldProjection = useFovSetting;
    }

    @ModifyConstant(method = "getProjectionMatrix", constant = @Constant(floatValue = 0.05F))
    private float vulkanmodnext$nearPlane(float vanilla) {
        if (!this.vulkanmodnext$worldProjection) {
            return vanilla;
        }
        int hundredths = VulkanConfig.get("nearPlaneHundredths");
        return hundredths <= 0 ? vanilla : hundredths / 100.0f;
    }
}
