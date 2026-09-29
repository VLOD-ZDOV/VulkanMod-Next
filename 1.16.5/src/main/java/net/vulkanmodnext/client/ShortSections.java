package net.vulkanmodnext.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.vulkanmodnext.mixin.ChunkRenderAccess;

import java.util.List;

/**
 * The visible sections that hold anything, gathered once a frame.
 *
 * <h2>The cost it removes</h2>
 *
 * The game's visible list is every section the search reached, and most of
 * them are air: at render distance 32 around 17 700 entries, of which under
 * 2 700 hold a block (the 1.12.2 count; the search is the same shape here).
 * Each layer then walks all of them and asks each one whether that layer is
 * empty, which is three dependent reads — the entry, the section, the
 * {@code AtomicReference} to its compiled result — before the set lookup that
 * answers. The terrain draw does that once per layer, four times a frame, and
 * the block-entity pass once more. On 1.12.2 the layer half measured 0.72 ms
 * a frame against 0.45 once shortened, and seven per cent of the frame rate
 * where the processor is what holds the frame back.
 *
 * <h2>Why a frame, and why the first asker builds it</h2>
 *
 * The 1.12.2 build kept this list while its own visibility search walked. This
 * port runs the game's search, so the list is made by one full walk the first
 * time anybody asks in a frame and handed to everybody after. The visible list
 * itself changes only inside {@code setupRender}, which comes before every
 * consumer; a section's compiled result is swapped only while uploads are
 * collected, which is before the layers too. A section that fills in later in
 * the same frame than that is picked up on the next one, which is the same
 * frame of lag a freshly built chunk always has.
 *
 * <h2>What is kept</h2>
 *
 * A section with any rendered block, or with any block entity. The second half
 * is not implied by the first: a chest draws nothing into the chunk mesh, so a
 * chest standing alone in a section of air holds no layer at all and would be
 * dropped by a test on blocks alone. The layers still ask each kept section
 * about their own layer; this only removes the ones where every answer is no.
 */
public final class ShortSections {

    private static final ObjectArrayList<Object> KEPT = new ObjectArrayList<>(4096);
    private static int frame;
    private static int builtOnFrame = -1;
    private static List<?> builtFrom;
    private static int builtFromSize;

    /** Entries walked in full to build the list, and entries the consumers walked. */
    private static long fullWalked;
    private static long shortWalked;
    private static long builds;

    private ShortSections() {
    }

    public static void beginFrame() {
        frame++;
    }

    /** This frame's kept entries of {@code visible}, in the same order. */
    public static ObjectArrayList<Object> of(List<?> visible) {
        if (builtOnFrame != frame || builtFrom != visible || builtFromSize != visible.size()) {
            build(visible);
        }
        shortWalked += KEPT.size();
        return KEPT;
    }

    private static void build(List<?> visible) {
        KEPT.clear();
        int n = visible.size();
        for (int i = 0; i < n; i++) {
            Object entry = visible.get(i);
            ChunkRenderDispatcher.CompiledChunk compiled =
                    ((ChunkRenderAccess) entry).vulkanmodnext$chunk().getCompiledChunk();
            if (!compiled.hasNoRenderableLayers()
                    || !compiled.getRenderableBlockEntities().isEmpty()) {
                KEPT.add(entry);
            }
        }
        fullWalked += n;
        builds++;
        builtOnFrame = frame;
        builtFrom = visible;
        builtFromSize = n;
    }

    public static String stats() {
        return "short sections: " + builds + " lists built from " + fullWalked
                + " entries, consumers walked " + shortWalked;
    }
}
