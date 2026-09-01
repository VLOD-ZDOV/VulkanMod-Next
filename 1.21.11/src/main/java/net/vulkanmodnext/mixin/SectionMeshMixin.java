package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.vulkanmodnext.client.ChunkGeometry;
import net.vulkanmodnext.client.ChunkMirror;
import net.vulkanmodnext.client.ChunkSlots;
import net.vulkanmodnext.client.SectionMeshSlots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes a copy of a section's geometry as the game uploads it, and gives every
 * layer of every section a small dense index of its own.
 *
 * <h2>One hook where 1.16.5 needs four</h2>
 *
 * On that version the geometry, the layer it belongs to, the buffer it is going
 * into and the moment it becomes drawable are four different places, and the
 * port has a mixin for each. Here they are one method: {@code uploadMeshLayer}
 * is handed the layer, the mesh and the section, on the render thread, before
 * anything has touched the bytes.
 *
 * <h2>At the head, and that is load-bearing</h2>
 *
 * The bytes are taken before vanilla's upload rather than after it. Vanilla
 * either writes the buffer through a command encoder or hands it to
 * {@code createBuffer}, and neither promises to leave the position where it
 * found it — a mirror that reads afterwards would copy a section that starts in
 * the middle of itself, which draws as spikes rather than as an error.
 */
@Mixin(CompiledSectionMesh.class)
public abstract class SectionMeshMixin implements SectionMeshSlots {

    /**
     * One slot per layer, made on first use.
     *
     * <h3>Why it is not simply initialised where it is declared</h3>
     *
     * Because Mixin refuses it: an array initialiser on a field becomes a run
     * of {@code IASTORE} in the initialiser, and merging that into somebody
     * else's constructor is a case it does not implement — "Cannot handle
     * IASTORE opcode (0x4F) in class initialiser", and the whole mixin is
     * dropped. It fails loudly, which is the good kind of failure, but it fails
     * at the point where the world is being built.
     *
     * <p>Guarded by the object's monitor on assignment and read plainly on the
     * render thread, which is the only thread that draws. Today both sides are
     * the render thread; the guard is what will still be true when the copy
     * moves onto the builder threads.
     */
    @Unique
    private volatile int[] vulkanmodnext$slots;

    @Unique
    private synchronized int[] vulkanmodnext$slotArray() {
        int[] slots = vulkanmodnext$slots;
        if (slots == null) {
            slots = new int[4];
            java.util.Arrays.fill(slots, ChunkSlots.UNASSIGNED);
            vulkanmodnext$slots = slots;
        }
        return slots;
    }

    @Override
    public int vulkanmodnext$slot(int layerOrdinal) {
        int[] slots = vulkanmodnext$slots;
        return slots == null ? ChunkSlots.UNASSIGNED : slots[layerOrdinal];
    }

    @Override
    public synchronized int vulkanmodnext$slotOrAssign(int layerOrdinal) {
        int[] slots = vulkanmodnext$slotArray();
        if (slots[layerOrdinal] == ChunkSlots.UNASSIGNED) {
            slots[layerOrdinal] = ChunkSlots.allocate();
        }
        return slots[layerOrdinal];
    }

    @Inject(method = "uploadMeshLayer", at = @At("HEAD"))
    private void vulkanmodnext$mirror(ChunkSectionLayer layer, MeshData mesh, long sectionNode,
                                      CallbackInfo ci) {
        int slot = vulkanmodnext$slotOrAssign(layer.ordinal());
        // Before the geometry: this is the last place that knows which layer
        // the slot is for, and the copy needs to know before it decides whether
        // it may sort the quads.
        ChunkMirror.onLayer(slot, layer == ChunkSectionLayer.TRANSLUCENT);
        ChunkGeometry.offer(slot, mesh);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vulkanmodnext$forget(CallbackInfo ci) {
        int[] slots = vulkanmodnext$slots;
        if (slots == null) {
            return;
        }
        for (int layer = 0; layer < slots.length; layer++) {
            int slot;
            synchronized (this) {
                slot = slots[layer];
                slots[layer] = ChunkSlots.UNASSIGNED;
            }
            if (slot != ChunkSlots.UNASSIGNED) {
                // Order matters: the mirror drops anything staged for this slot
                // and bumps its epoch, so nothing can publish into it, and only
                // then does the slot go back for reuse.
                ChunkMirror.onBufferDelete(slot);
                ChunkSlots.release(slot);
            }
        }
    }
}
