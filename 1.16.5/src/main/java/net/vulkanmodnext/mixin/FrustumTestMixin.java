package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.util.math.vector.Vector4f;
import net.vulkanmodnext.client.CpuSavings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tests a box against the view frustum by its far corner instead of all eight.
 *
 * Vanilla asks, for each of the six planes, whether every one of the box's
 * eight corners is on the outside of it, and builds a fresh {@code Vector4f}
 * for each corner to ask with:
 *
 * <pre>
 * if (!(plane.dot(new Vector4f(minX, minY, minZ, 1)) &gt; 0) &amp;&amp; ... eight of them)
 *     return false;
 * </pre>
 *
 * Rejecting one box is up to forty-eight dot products and forty-eight
 * allocations, and the visibility search in {@code setupRender} asks this for
 * every chunk it reaches — {@code setFrame} marks a chunk before the test, so
 * none is asked twice, but at a long render distance that is still tens of
 * thousands of boxes each time the camera moves. The same cost is what the
 * 1.12.2 build removed; 1.16.5 kept vanilla's loop and added the allocations.
 *
 * <h2>Why the answer is the same, to the last bit</h2>
 *
 * {@code dot} is {@code ((x·px + y·py) + z·pz) + w·1}, in floats. Rounded
 * multiplication and addition are both monotonic, so each product is largest
 * at the coordinate the plane's normal points towards and each partial sum is
 * then largest too: the far corner's dot is the maximum of the eight as the
 * game computes them, not an estimate of it. "No corner is inside" is exactly
 * "the far corner is not inside". The comparison is written as vanilla's,
 * {@code !(d > 0)}, so a NaN plane rejects here as it rejects there.
 *
 * <h2>The one case handed back</h2>
 *
 * Forge gives some block entities an infinite render box. An infinite
 * coordinate times a normal component of exactly zero is NaN, which vanilla
 * counts as that corner being outside while the far corner picks a finite
 * coordinate instead; the two would disagree. A box with a non-finite
 * coordinate therefore goes to vanilla.
 */
@Mixin(ClippingHelper.class)
public abstract class FrustumTestMixin {

    @Shadow
    @Final
    private Vector4f[] frustumData;

    @Inject(method = "cubeInFrustum(FFFFFF)Z", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$farCornerTest(float minX, float minY, float minZ,
                                            float maxX, float maxY, float maxZ,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!CpuSavings.fastFrustumTest
                || vulkanmodnext$notFinite(minX) || vulkanmodnext$notFinite(maxX)
                || vulkanmodnext$notFinite(minY) || vulkanmodnext$notFinite(maxY)
                || vulkanmodnext$notFinite(minZ) || vulkanmodnext$notFinite(maxZ)) {
            CpuSavings.countFrustumTest(false);
            return;
        }
        CpuSavings.countFrustumTest(true);
        Vector4f[] planes = this.frustumData;
        for (int i = 0; i < 6; i++) {
            Vector4f plane = planes[i];
            float nx = plane.x();
            float ny = plane.y();
            float nz = plane.z();
            // The same expression, in the same order, as Vector4f.dot with a
            // w of one: evaluated differently it could round differently.
            float far = (nx > 0.0F ? maxX : minX) * nx
                    + (ny > 0.0F ? maxY : minY) * ny
                    + (nz > 0.0F ? maxZ : minZ) * nz
                    + plane.w() * 1.0F;
            if (!(far > 0.0F)) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }

    @Unique
    private static boolean vulkanmodnext$notFinite(float v) {
        return v - v != 0.0F;
    }
}
