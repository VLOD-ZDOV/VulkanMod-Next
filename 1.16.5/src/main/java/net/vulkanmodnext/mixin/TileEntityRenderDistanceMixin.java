package net.vulkanmodnext.mixin;

import net.minecraft.tileentity.TileEntity;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps how far block entities are drawn.
 *
 * Chests, signs and banners each cost a separate draw with their own model and
 * texture, and vanilla's default reach of 64 blocks applies to every one of
 * them. Lowering it is felt immediately in storage rooms.
 *
 * <h2>A distance here, not a square</h2>
 *
 * On 1.12.2 the method was {@code getMaxRenderDistanceSquared} and the answer
 * was the limit squared. Its 1.16.5 counterpart, {@code getViewDistance},
 * returns 64 and {@code TileEntityRendererDispatcher.render} hands it to
 * {@code closerThan}, which squares it itself — so the answer is the limit as
 * it is. Carrying the square across would have made a limit of 16 into 256
 * blocks, four times vanilla's reach, and the setting would have looked like
 * it did nothing at all.
 *
 * Block entities that override it — beacons, end gateways, pistons, structure
 * blocks — keep their own reach, as they did on 1.12.2: those are drawn far on
 * purpose, and they are not what fills a storage room.
 */
@Mixin(TileEntity.class)
public abstract class TileEntityRenderDistanceMixin {

    @Inject(method = "getViewDistance", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$limitDistance(CallbackInfoReturnable<Double> cir) {
        int limit = VulkanConfig.get("tileEntityDistance");
        if (limit > 0) {
            cir.setReturnValue((double) limit);
        }
    }
}
