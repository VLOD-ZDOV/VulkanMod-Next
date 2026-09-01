package net.vulkanmodnext.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmodnext.VulkanModNext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a built section actually contains, checked against what we believe it
 * contains.
 *
 * <h2>Why this is the first thing the port does with a section</h2>
 *
 * Everything downstream — the mirror, the vertex layout, the packing, the
 * facing groups — is written against one belief about the shape of a vertex.
 * On 1.12.2 that shape is 28 bytes and carries no normal; on 1.16.5 and here it
 * is <b>32</b> with a normal in it. A stride that is wrong by four bytes
 * produces a world of spikes rather than an error, and this project has already
 * spent a day on exactly that.
 *
 * <p>So the belief is written down here as a number, and the first sections the
 * game builds are measured against it. The check says which of the two it is —
 * "the format is not what we expect" and "the format is what we expect and the
 * numbers in it are nonsense" need different work.
 */
public final class ChunkGeometry {

    /** POSITION 12 + COLOR 4 + UV0 8 + UV2 4 + NORMAL 3 + PADDING 1. */
    private static final int EXPECTED_STRIDE = 32;

    /** Only the first few: this is a check, not a running cost. */
    private static final int SECTIONS_TO_CHECK = 3;

    private static final AtomicInteger checked = new AtomicInteger();

    /** How many layers to see before saying whether the mirror is taking them. */
    private static final int ANNOUNCE_AFTER = 200;

    private static final AtomicInteger mirrored = new AtomicInteger();
    private static final AtomicInteger refused = new AtomicInteger();
    private static final AtomicLong mirroredBytes = new AtomicLong();
    private static volatile boolean announced;
    private static volatile boolean complained;

    private ChunkGeometry() {
    }

    /**
     * Called as the game uploads a section layer, before it has touched the
     * bytes.
     *
     * <p>At the head of the upload rather than at its return, and that is not a
     * preference: {@code uploadMeshLayer} either writes the buffer through a
     * command encoder or hands it to {@code createBuffer}, and neither promises
     * to leave the position where it found it. A view is taken here, while the
     * only reader so far is us.
     *
     * @return the number of bytes mirrored, or zero
     */
    public static int offer(int slot, MeshData mesh) {
        try {
            MeshData.DrawState state = mesh.drawState();
            int vertices = state.vertexCount();
            int stride = state.format().getVertexSize();
            if (vertices == 0) {
                return 0;
            }
            if (checked.get() < SECTIONS_TO_CHECK) {
                describe(mesh, state);
            }

            ByteBuffer whole = mesh.vertexBuffer();
            int length = vertices * stride;
            if (length <= 0 || length > whole.remaining()) {
                return 0;
            }
            // A view, not a copy, and never the buffer itself: the upload that
            // follows this hook reads the same object, and moving its position
            // would hand the game a section that starts in the middle of
            // itself.
            ByteBuffer layer = whole.duplicate();
            layer.order(ByteOrder.LITTLE_ENDIAN);
            layer.limit(layer.position() + length);

            ChunkMirror.onBufferData(slot, layer);
            mirrored.incrementAndGet();
            mirroredBytes.addAndGet(length);
            announceOnce();
            return length;
        } catch (Throwable failed) {
            // A copy must never be the thing that breaks a section: whatever
            // happens here, the game still uploads the same geometry it always
            // did.
            refused.incrementAndGet();
            if (!complained) {
                complained = true;
                VulkanModNext.LOGGER.warn("Could not mirror a built section, the game is "
                        + "unaffected", failed);
            }
            return 0;
        }
    }

    /**
     * Says once, out loud, whether the mirror is actually receiving anything.
     *
     * A count that is quietly zero is the failure this port is most likely to
     * have and least likely to notice: everything starts, nothing is mirrored,
     * and the log looks the same as a working one.
     */
    private static void announceOnce() {
        if (announced) {
            return;
        }
        int done = mirrored.get();
        int missed = refused.get();
        if (done + missed < ANNOUNCE_AFTER) {
            return;
        }
        announced = true;
        if (done == 0) {
            VulkanModNext.LOGGER.warn("Section mirror: {} layers offered and none taken — the "
                    + "geometry is being read but Vulkan is not keeping it", missed);
            return;
        }
        VulkanModNext.LOGGER.info("Section mirror: {} of {} layers copied into Vulkan, {} KiB",
                done, done + missed, mirroredBytes.get() / 1024);
    }

    private static void describe(MeshData mesh, MeshData.DrawState state) {
        VertexFormat format = state.format();
        int vertices = state.vertexCount();
        int stride = format.getVertexSize();
        if (checked.getAndIncrement() >= SECTIONS_TO_CHECK) {
            return;
        }

        StringBuilder elements = new StringBuilder();
        for (VertexFormatElement element : format.getElements()) {
            if (elements.length() != 0) {
                elements.append(" + ");
            }
            elements.append(element.usage()).append(':')
                    .append(element.count()).append('x').append(element.type());
        }

        boolean asExpected = stride == EXPECTED_STRIDE && format == DefaultVertexFormat.BLOCK;
        String verdict = asExpected ? "as expected"
                : "NOT what the port is written for (expected " + EXPECTED_STRIDE
                        + " bytes of DefaultVertexFormat.BLOCK)";

        ByteBuffer bytes = mesh.vertexBuffer().duplicate();
        bytes.order(ByteOrder.LITTLE_ENDIAN);
        int at = bytes.position();

        VulkanModNext.LOGGER.info("Section layer: {} vertices, {} bytes each — {}. Layout: {}",
                vertices, stride, verdict, elements);
        if (stride >= EXPECTED_STRIDE && at + stride <= bytes.limit()) {
            // The first vertex, read the way the renderer would read it. A
            // position outside a sixteen-block cube is the clearest sign that
            // the stride or the offsets are wrong, and it costs one line to
            // see.
            VulkanModNext.LOGGER.info(
                    "  first vertex: position ({}, {}, {}), colour {}, uv ({}, {}), light {}/{}",
                    bytes.getFloat(at), bytes.getFloat(at + 4), bytes.getFloat(at + 8),
                    String.format("%08X", bytes.getInt(at + 12)),
                    String.format("%.4f", bytes.getFloat(at + 16)),
                    String.format("%.4f", bytes.getFloat(at + 20)),
                    bytes.getShort(at + 24) & 0xFFFF, bytes.getShort(at + 26) & 0xFFFF);
        }
    }
}
