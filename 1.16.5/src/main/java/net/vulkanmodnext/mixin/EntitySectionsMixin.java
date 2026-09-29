package net.vulkanmodnext.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.renderer.WorldRenderer;
import net.vulkanmodnext.client.AnimatedSprites;
import net.vulkanmodnext.client.CpuSavings;
import net.vulkanmodnext.client.ShortSections;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hands the block-entity pass only the sections that can hold a block entity.
 *
 * <h2>Half of the 1.12.2 setting applies here</h2>
 *
 * On 1.12.2 both passes walked every visible section and asked the world about
 * each one: the entity pass for the section's creature list, the block-entity
 * pass for its compiled result. On 1.16.5 the entity pass already walks the
 * creatures themselves ({@code ClientWorld.entitiesForRendering}) and asks the
 * frustum about each one, which is the inside-out loop the 1.12.2 build had to
 * write — so there is nothing left to take from it.
 *
 * The block-entity pass is unchanged from 1.12.2 in shape: it walks the whole
 * visible list, some 17 700 entries at render distance 32, and reads each
 * section's compiled result to find the list of block entities empty. It is
 * given the shortened list from {@link ShortSections} instead, which keeps
 * every section with a block entity by construction, in the same order.
 *
 * {@code renderLevel} fetches an iterator from the visible list exactly once,
 * and it is this pass's.
 */
@Mixin(WorldRenderer.class)
public abstract class EntitySectionsMixin {

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lit/unimi/dsi/fastutil/objects/ObjectList;iterator()"
                            + "Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ObjectListIterator vulkanmodnext$blockEntitySections(ObjectList visible) {
        // Smart animations gather their visible set here too, for the same
        // reason this pass is shortened here: it is the one walk of the visible
        // list that happens every frame after the frame's uploads, whether the
        // terrain goes to Vulkan or not. Sections holding nothing use no
        // sprite, so the shortened list is the whole answer.
        if (CpuSavings.smartAnimations) {
            AnimatedSprites.markVisible(ShortSections.of(visible));
        }
        if (!CpuSavings.shortEntitySections) {
            CpuSavings.countBlockEntityWalk(visible.size());
            return visible.iterator();
        }
        ObjectList kept = ShortSections.of(visible);
        CpuSavings.countBlockEntityWalk(kept.size());
        return kept.iterator();
    }
}
