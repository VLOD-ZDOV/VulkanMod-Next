package net.vulkanmodnext.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockDisplayReader;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.data.EmptyModelData;
import net.vulkanmodnext.VulkanModNext;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Which animated sprites are actually in front of you, so that the rest can be
 * left alone.
 *
 * <h2>What vanilla does</h2>
 *
 * Once a tick the block atlas steps every sprite that has an animation, and a
 * sprite whose frame changed uploads it — to OpenGL, and through
 * {@link AtlasAnimations} to the Vulkan copy of the atlas as well, which is a
 * pixel copy on this thread on top of the driver's. Every one, every tick,
 * whether or not a single block using it is on screen: water, lava, fire,
 * portals, sea lanterns, prismarine, magma, and in a modpack several hundred
 * machines besides. The same cost the 1.12.2 build removed; 1.16.5 steps the
 * list exactly as 1.12.2 did.
 *
 * <h2>What this does instead</h2>
 *
 * A chunk records which animated sprites its blocks use while it is being
 * built, which is the one moment the answer is knowable at all — after that
 * the geometry is texture coordinates in a buffer and the sprite is gone. The
 * record is kept on the chunk's compiled result, so it is swapped in together
 * with the geometry it describes. Each frame the sections that are being drawn
 * contribute their sprites to one set, and the tick steps that set rather than
 * all of them.
 *
 * <h2>The rules that keep it honest</h2>
 *
 * A section with no record contributes <em>everything</em>. That covers the
 * chunks built before this was switched on: the failure is "no saving", never
 * "the world stopped moving". A set not gathered for a second — the world
 * closed, or the hook that gathers it gone — counts as unknown too.
 *
 * Anything drawn that is not terrain is vouched for another way. An item model
 * marks its sprites as it is drawn — in the hand, in a menu, in a frame or on
 * the ground — and they stay marked for a second or two. Water, lava, fire
 * and portals are always stepped, by name: fire burns on creatures as well as
 * on blocks, and they are what a player looks at to see whether the game is
 * still running. Fluids met in a chunk are recorded through Forge's own lookup
 * of their sprites, so a mod's fluid is covered like a block is.
 *
 * <p>What remains, and it is why this ships switched off as on 1.12.2: an
 * animated block texture drawn by something that is neither a chunk nor an
 * item model — a falling block, a block in an enderman's arms, a mod's own
 * geometry — stands still while no section in sight uses it. So does a block
 * whose model picks its quads from model data (connected textures): its
 * sprites are read from the model without that data.
 */
public final class AnimatedSprites {

    private static final int BITS = 64;

    /** The animated sprites of the block atlas, in the order the atlas holds them. */
    private static TextureAtlasSprite[] sprites = new TextureAtlasSprite[0];
    private static List<TextureAtlasSprite> indexedFrom;
    private static Map<TextureAtlasSprite, Integer> position = new IdentityHashMap<>();
    private static int words;

    /** Stepped whatever is on screen: water, lava, fire and portals, by name. */
    private static long[] always = new long[0];

    /** What the sections drawn in the last frame between them use. */
    private static volatile long[] wanted = new long[0];
    private static long[] gathering = new long[0];
    private static volatile long gatheredAt;

    /**
     * Sprites an item model has drawn with lately, in two buckets swapped once
     * a second: between one and two seconds of memory, which is longer than any
     * gap between two draws of the same item — a menu closing for a frame must
     * not freeze what is in the hand — and short enough to be worth having.
     */
    private static long[] itemsNow = new long[0];
    private static long[] itemsBefore = new long[0];
    private static long itemsSwappedAt;

    /**
     * Item models met so far and their sprites. Emptied when it grows past
     * this rather than left to grow: a mod that bakes a model per stack would
     * otherwise keep every one of them alive.
     */
    private static final Map<Object, long[]> BY_ITEM_MODEL = new IdentityHashMap<>();
    private static final int ITEM_MODELS_KEPT = 4096;

    /**
     * The sprites of a block or fluid state, worked out once. States are
     * singletons, so identity is the right comparison and the cheapest one. A
     * state whose model refuses to answer is stored with everything set, the
     * safe direction. Shared by the builder threads, hence the lock.
     */
    private static final Map<Object, long[]> BY_STATE = new IdentityHashMap<>();

    private static final ThreadLocal<Build> BUILDING = new ThreadLocal<>();

    private static volatile boolean ready;

    /** Sprites stepped and skipped, per tick and in total, for the report. */
    private static volatile int lastStepped;
    private static long stepped;
    private static long skipped;

    /**
     * How many item models were looked at — a sentinel, not a statistic. Zero
     * with the setting on and a world open is the item hook missing, and then
     * every animated texture in the hand stands still with nothing else to
     * say why.
     */
    private static volatile long itemModelCalls;

    private AnimatedSprites() {
    }

    /**
     * Takes the atlas's list of animated sprites and numbers them.
     *
     * Called from the tick that steps them, because that is the one place the
     * list is certainly the current one — a resource reload builds a new atlas
     * and every sprite object with it, and fills the same list object anew.
     */
    public static synchronized void index(List<TextureAtlasSprite> animated) {
        if (ready && indexedFrom == animated && sprites.length == animated.size()
                && (sprites.length == 0 || sprites[0] == animated.get(0))) {
            return;
        }
        indexedFrom = animated;
        sprites = animated.toArray(new TextureAtlasSprite[0]);
        Map<TextureAtlasSprite, Integer> at = new IdentityHashMap<>(sprites.length * 2);
        for (int i = 0; i < sprites.length; i++) {
            at.put(sprites[i], i);
        }
        int count = (sprites.length + BITS - 1) / BITS;
        long[] fixed = new long[count];
        for (int i = 0; i < sprites.length; i++) {
            String name = sprites[i].getName().getPath();
            if (name.contains("water") || name.contains("lava")
                    || name.contains("fire") || name.contains("portal")) {
                set(fixed, i);
            }
        }
        synchronized (BY_STATE) {
            BY_STATE.clear();
            position = at;
            words = count;
        }
        synchronized (BY_ITEM_MODEL) {
            BY_ITEM_MODEL.clear();
        }
        always = fixed;
        itemsNow = new long[count];
        itemsBefore = new long[count];
        gathering = new long[count];
        wanted = new long[count];
        gatheredAt = 0L;
        ready = true;
        VulkanModNext.LOGGER.info("Smart animations: {} animated sprites in the block atlas",
                sprites.length);
    }

    /** A chunk is about to be built on this thread. */
    public static void beginChunk() {
        if (!ready) {
            return;
        }
        Build build = BUILDING.get();
        if (build == null) {
            build = new Build();
            BUILDING.set(build);
        }
        // A fresh array every time: the previous one was handed to that chunk.
        build.mask = new long[words];
        build.reset();
    }

    /**
     * One block of the chunk being built on this thread. Called per block, so
     * the common case is one identity probe in a table only this thread
     * touches; the shared map is asked once per distinct state per chunk.
     */
    public static void recordBlock(BlockState state) {
        Build build = BUILDING.get();
        if (build == null || build.mask == null || !build.remember(state)) {
            return;
        }
        long[] ofState;
        synchronized (BY_STATE) {
            ofState = BY_STATE.get(state);
        }
        if (ofState == null) {
            ofState = blockMask(state);
        }
        build.or(ofState);
    }

    /** One fluid of the chunk being built on this thread. */
    public static void recordFluid(FluidState fluid, IBlockDisplayReader world, BlockPos pos) {
        Build build = BUILDING.get();
        if (build == null || build.mask == null || !build.remember(fluid)) {
            return;
        }
        long[] ofFluid;
        synchronized (BY_STATE) {
            ofFluid = BY_STATE.get(fluid);
        }
        if (ofFluid == null) {
            ofFluid = fluidMask(fluid, world, pos);
        }
        build.or(ofFluid);
    }

    /** @return what the chunk just built on this thread uses, or null if nothing was recorded */
    public static long[] finishChunk() {
        Build build = BUILDING.get();
        if (build == null || build.mask == null) {
            return null;
        }
        long[] mask = build.mask;
        build.mask = null;
        build.reset();
        return mask;
    }

    /**
     * The sections being drawn this frame. Only sections holding something are
     * handed over; an empty one uses no sprite.
     */
    public static void markVisible(List<?> sections) {
        if (!ready) {
            return;
        }
        long[] gather = gathering;
        int count = words;
        if (gather.length != count) {
            return;
        }
        java.util.Arrays.fill(gather, 0L);
        for (int i = 0; i < sections.size(); i++) {
            long[] mask = ((SpriteMarked) ((net.vulkanmodnext.mixin.ChunkRenderAccess) sections.get(i))
                    .vulkanmodnext$chunk().getCompiledChunk()).vulkanmodnext$animatedSprites();
            if (mask == null || mask.length != count) {
                // Built before this was switched on, or before the atlas was
                // last reloaded. Nothing is known about it, so nothing may be
                // skipped on its account.
                java.util.Arrays.fill(gather, -1L);
                break;
            }
            for (int w = 0; w < count; w++) {
                gather[w] |= mask[w];
            }
        }
        gathering = wanted;
        wanted = gather;
        gatheredAt = System.currentTimeMillis();
    }

    /** Steps the sprites that are wanted and leaves the rest where they are. */
    public static void stepWanted(List<TextureAtlasSprite> animated) {
        index(animated);
        long[] visible = wanted;
        int count = words;
        boolean fresh = System.currentTimeMillis() - gatheredAt < 1000L
                && visible.length == count;
        int n = 0;
        for (int i = 0; i < sprites.length; i++) {
            if (!fresh || get(always, i) || get(itemsNow, i) || get(itemsBefore, i)
                    || get(visible, i)) {
                sprites[i].cycleFrames();
                n++;
            }
        }
        lastStepped = n;
        stepped += n;
        skipped += sprites.length - n;
    }

    /**
     * An item model is being drawn: whatever it is made of has to keep moving.
     * The hand, the inventory, a dropped stack and an item frame all come
     * through the one method this is called from.
     */
    public static void recordItemModel(IBakedModel model) {
        if (!ready) {
            return;
        }
        itemModelCalls++;
        long[] mask;
        synchronized (BY_ITEM_MODEL) {
            mask = BY_ITEM_MODEL.get(model);
        }
        if (mask == null) {
            mask = new long[words];
            Random random = new Random();
            try {
                for (Direction face : Direction.values()) {
                    random.setSeed(42L);
                    collect(model.getQuads(null, face, random), mask);
                }
                random.setSeed(42L);
                collect(model.getQuads(null, null, random), mask);
            } catch (Throwable t) {
                java.util.Arrays.fill(mask, -1L);
            }
            synchronized (BY_ITEM_MODEL) {
                if (BY_ITEM_MODEL.size() >= ITEM_MODELS_KEPT) {
                    BY_ITEM_MODEL.clear();
                }
                BY_ITEM_MODEL.put(model, mask);
            }
        }
        long now = System.currentTimeMillis();
        if (now - itemsSwappedAt > 1000L) {
            itemsSwappedAt = now;
            long[] swap = itemsBefore;
            itemsBefore = itemsNow;
            java.util.Arrays.fill(swap, 0L);
            itemsNow = swap;
        }
        long[] into = itemsNow;
        for (int i = 0; i < into.length && i < mask.length; i++) {
            into[i] |= mask[i];
        }
    }

    public static String stats() {
        if (!ready) {
            return "smart animations: atlas not indexed";
        }
        boolean fresh = System.currentTimeMillis() - gatheredAt < 1000L;
        return "smart animations: " + lastStepped + " of " + sprites.length
                + " animated sprites stepped last tick"
                + (fresh ? "" : " (no visible set, everything stepped)")
                + ", " + stepped + " stepped and " + skipped + " skipped in all"
                + ", item models seen " + itemModelCalls;
    }

    /**
     * Which animated sprites a block state draws with, asked of its model:
     * a block does not know its textures, and one state can carry a different
     * model in every resource pack. Everything is set on any failure at all.
     */
    private static long[] blockMask(BlockState state) {
        long[] mask = new long[words];
        try {
            IBakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            Random random = new Random();
            for (Direction face : Direction.values()) {
                random.setSeed(42L);
                collect(model.getQuads(state, face, random, EmptyModelData.INSTANCE), mask);
            }
            random.setSeed(42L);
            collect(model.getQuads(state, null, random, EmptyModelData.INSTANCE), mask);
        } catch (Throwable t) {
            java.util.Arrays.fill(mask, -1L);
        }
        synchronized (BY_STATE) {
            BY_STATE.put(state, mask);
        }
        return mask;
    }

    /**
     * A fluid draws with the sprites Forge hands its renderer, and asking
     * Forge is what covers a mod's fluid as well as water and lava.
     */
    private static long[] fluidMask(FluidState fluid, IBlockDisplayReader world, BlockPos pos) {
        long[] mask = new long[words];
        try {
            TextureAtlasSprite[] used = ForgeHooksClient.getFluidSprites(world, pos, fluid);
            Map<TextureAtlasSprite, Integer> at = position;
            for (TextureAtlasSprite sprite : used) {
                Integer i = sprite == null ? null : at.get(sprite);
                if (i != null && i < mask.length * BITS) {
                    set(mask, i);
                }
            }
        } catch (Throwable t) {
            java.util.Arrays.fill(mask, -1L);
        }
        synchronized (BY_STATE) {
            BY_STATE.put(fluid, mask);
        }
        return mask;
    }

    private static void collect(List<BakedQuad> quads, long[] mask) {
        Map<TextureAtlasSprite, Integer> at = position;
        for (int i = 0; i < quads.size(); i++) {
            Integer index = at.get(quads.get(i).getSprite());
            if (index != null && index < mask.length * BITS) {
                set(mask, index);
            }
        }
    }

    private static void set(long[] bits, int index) {
        bits[index >> 6] |= 1L << (index & 63);
    }

    private static boolean get(long[] bits, int index) {
        return (bits[index >> 6] & (1L << (index & 63))) != 0L;
    }

    /**
     * One chunk build, on one thread. The states already dealt with in this
     * chunk are kept in an open-addressed table by identity: a {@code HashSet}
     * would call {@code hashCode} on the state, which in this version walks its
     * property map, every block. Nothing here is shared, so nothing needs a
     * lock, and the table is kept between chunks.
     */
    private static final class Build {

        long[] mask;
        private Object[] seen = new Object[128];
        private int seenCount;

        void reset() {
            if (seenCount != 0) {
                java.util.Arrays.fill(seen, null);
                seenCount = 0;
            }
        }

        /**
         * Bounded by both lengths: a resource reload replaces the atlas and
         * the word count with it, and a chunk that began before it would
         * otherwise run off the end of one array or the other.
         */
        void or(long[] bits) {
            long[] into = mask;
            int shared = Math.min(into.length, bits.length);
            for (int i = 0; i < shared; i++) {
                into[i] |= bits[i];
            }
        }

        /** @return true when this state has not been seen in this chunk before */
        boolean remember(Object state) {
            Object[] table = seen;
            int wrap = table.length - 1;
            int at = spread(System.identityHashCode(state)) & wrap;
            while (true) {
                Object there = table[at];
                if (there == null) {
                    table[at] = state;
                    seenCount++;
                    if (seenCount * 2 > table.length) {
                        grow();
                    }
                    return true;
                }
                if (there == state) {
                    return false;
                }
                at = (at + 1) & wrap;
            }
        }

        private void grow() {
            Object[] bigger = new Object[seen.length * 2];
            int wrap = bigger.length - 1;
            for (Object state : seen) {
                if (state == null) {
                    continue;
                }
                int at = spread(System.identityHashCode(state)) & wrap;
                while (bigger[at] != null) {
                    at = (at + 1) & wrap;
                }
                bigger[at] = state;
            }
            seen = bigger;
        }

        /** Identity hashes of objects born together share their low bits. */
        private static int spread(int hash) {
            return hash ^ (hash >>> 16);
        }
    }

    /** A compiled chunk that remembers which animated sprites its blocks use. */
    public interface SpriteMarked {

        long[] vulkanmodnext$animatedSprites();

        void vulkanmodnext$animatedSprites(long[] mask);
    }
}
