package net.vulkanmodnext.client;

/**
 * Implemented on {@code CompiledSectionMesh} by the mirror mixin, so the draw
 * path can ask a section where its mirror entries live without a lookup.
 *
 * <h2>Why the mesh and not the buffer</h2>
 *
 * On 1.16.5 the slot lives on the {@code VertexBuffer}, because that is the
 * object the upload hook is handed. Here the upload hook is handed a
 * {@code ChunkSectionLayer}, a {@code MeshData} and the section's position —
 * and the {@code SectionBuffers} it is about to write into <em>may not exist
 * yet</em>: the first upload of a layer creates it. Taking the slot from
 * something that is null half the time is how a mirror ends up with holes in
 * it, so the slot is kept one level up, on the mesh, which exists for the whole
 * life of the section.
 *
 * <p>One slot per layer: a section can have geometry in four of them and each
 * is drawn separately.
 */
public interface SectionMeshSlots {

    /** The slot for this layer, or {@link ChunkSlots#UNASSIGNED} if none yet. */
    int vulkanmodnext$slot(int layerOrdinal);

    /**
     * The slot for this layer, assigning one if it has none.
     *
     * Callable from the render thread and, once worker staging arrives, from
     * the chunk builder threads — so it has to be atomic: a plain
     * check-then-assign would let two threads take a slot each for the same
     * layer and leak one of them.
     */
    int vulkanmodnext$slotOrAssign(int layerOrdinal);
}
