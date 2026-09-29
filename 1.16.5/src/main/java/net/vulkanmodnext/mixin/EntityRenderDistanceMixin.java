package net.vulkanmodnext.mixin;

import net.minecraft.entity.Entity;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps how far entities are drawn.
 *
 * Vanilla decides per entity from its bounding box, which for large mobs
 * reaches much further than a player can make out. {@code EntityRenderer
 * .shouldRender} asks this method first, before the frustum test, and
 * {@code WorldRenderer} asks that before drawing anything, so a distance limit
 * here removes the whole cost of an entity — model, texture binds and all.
 *
 * On 1.12.2 this was {@code isInRangeToRender3d}; it is the same method under
 * its 1.16.5 name, taking the camera position the same way.
 */
@Mixin(Entity.class)
public abstract class EntityRenderDistanceMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$limitDistance(double x, double y, double z,
                                            CallbackInfoReturnable<Boolean> cir) {
        int limit = VulkanConfig.get("entityDistance");
        if (limit <= 0) {
            return;
        }
        Entity self = (Entity) (Object) this;
        double dx = self.getX() - x;
        double dy = self.getY() - y;
        double dz = self.getZ() - z;
        if (dx * dx + dy * dy + dz * dz > (double) limit * limit) {
            cir.setReturnValue(false);
        }
    }
}
