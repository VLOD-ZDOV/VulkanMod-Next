package net.vulkanmodnext.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.renderer.model.ModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The boxes of one model part, which the game keeps to itself.
 *
 * Only counted, never read: the drawing path replays the game's own
 * {@code render} into a builder of ours, so the geometry reaches Vulkan through
 * the same arithmetic that would have put it on the screen. This is for the
 * capture's report — how many quads a frame of creatures is.
 */
@Mixin(ModelRenderer.class)
public interface ModelPartAccess {

    @Accessor("cubes")
    ObjectList<ModelRenderer.ModelBox> vulkanmodnext$cubes();
}
