package net.vulkanmodnext.client;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.monster.CreeperEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.vulkanmodnext.vkimpl.VkContext;

/**
 * Finds the things in the world that ought to be giving off light, and hands
 * them to the terrain shader.
 *
 * The obvious way to do this is vanilla's own: raise the light level stored in
 * the world and let the chunk be rebuilt. That is what makes dynamic lighting
 * expensive — chunk rebuilding is what the frame is waiting on while the player
 * moves, and a torch carried at walking pace would rebuild chunks continuously,
 * which is the one thing not to spend a frame on.
 *
 * So nothing is rebuilt. The light sources are collected here once a frame,
 * handed over as positions relative to the camera, and the terrain shader adds
 * their contribution while it is already shading the pixel. What it costs is
 * arithmetic on fragments that were going to be shaded anyway.
 *
 * The cost of collecting them is the loop below, and it is bounded twice: by a
 * radius, and by a hard cap on how many are passed on.
 *
 * <h2>What differs from 1.12.2</h2>
 *
 * The held item needs no hook of its own here. On 1.12.2 the first-person
 * renderer read the world directly at the player's eye, past every entity
 * hook, and needed a mixin of its own. On this version it is handed
 * {@code EntityRendererManager.getPackedLightCoords(player, ...)} — the same
 * call every entity is lit through — so {@code EntityLightMixin} covers both.
 */
public final class DynamicLights {

    /** Positions and levels; four floats each, matching the shader's vec4. */
    public static final int MAX_LIGHTS = 32;
    private static final float[] LIGHTS = new float[MAX_LIGHTS * 4];
    private static int count;

    /** Where the camera was when the list was filled; the stored positions are relative to it. */
    private static double originX;
    private static double originY;
    private static double originZ;

    /**
     * Which frame the list was last filled for. The terrain hands over its
     * per-frame state once per layer, four times a frame, and the block sweep
     * below advances a fixed number of sections per call — gathering on every
     * layer would run it four times as fast as it was tuned for and scan the
     * entity list four times for one answer.
     */
    private static int gatheredFrame = -1;

    /**
     * The world the list was filled in. Positions are relative to the camera,
     * so after a change of world or dimension the block list would light the
     * new one with the old one's torches until the next sweep finished. There
     * is no world-change hook on this port yet; comparing the object is one.
     */
    private static ClientWorld lastLevel;

    private static long gathered;
    private static long scanned;
    private static long frames;
    private static long entityAsked;
    private static long entityRaised;

    private DynamicLights() {
    }

    public static float[] lights() {
        return LIGHTS;
    }

    public static int count() {
        return count;
    }

    /**
     * Fills the list for this frame, once, and sends it.
     *
     * @param frame the terrain's frame counter; a second call with the same
     *              one only resends what is already there
     */
    public static void handOver(VkContext context, int frame,
                                double viewX, double viewY, double viewZ) {
        if (frame != gatheredFrame) {
            gatheredFrame = frame;
            gather(viewX, viewY, viewZ);
            // Sent even when empty: a count of zero is what turns the lights
            // off in the shader, and not sending it would leave the last list
            // lit after the setting is switched off.
            context.updateDynamicLights(LIGHTS, count);
        }
    }

    /**
     * Collects the light sources near the camera, in camera-relative
     * coordinates — the same space the terrain shader works in, so nothing has
     * to be transformed on the way through.
     */
    private static void gather(double viewX, double viewY, double viewZ) {
        count = 0;
        originX = viewX;
        originY = viewY;
        originZ = viewZ;
        Minecraft mc = Minecraft.getInstance();
        ClientWorld level = mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            BlockLightSources.forget();
        }
        if (!VulkanConfig.on("dynamicLights") || level == null) {
            return;
        }
        frames++;
        // Read once per frame: a setting change takes effect on the next frame
        // rather than partway through a list.
        double radius = VulkanConfig.get("dynamicLightDistance");
        double radiusSq = radius * radius;
        // Where the entities are being drawn, not where they were at the last
        // tick — the same lerp WorldRenderer.renderEntity applies. Raw tick
        // positions here would move the distance between a torch and the ground
        // in twenty steps a second while the view moved with every frame, and
        // the top of a block beside a jumping player flickered on 1.12.2 for
        // exactly that reason.
        float partial = mc.getFrameTime();
        // With a radius this wide the cap can be reached, and taking whichever
        // sources happen to come first in the world's list would make lights
        // wink in and out as entities are added and removed. The farthest is
        // dropped instead, so what survives is the nearest.
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null) {
                continue;
            }
            scanned++;
            double dx = entity.xOld + (entity.getX() - entity.xOld) * partial - viewX;
            double dy = entity.yOld + (entity.getY() - entity.yOld) * partial - viewY;
            double dz = entity.zOld + (entity.getZ() - entity.zOld) * partial - viewZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            int light = lightLevel(entity);
            if (light <= 0) {
                continue;
            }
            // Entity positions are at the feet; a carried light belongs at
            // roughly eye height, and a dropped one just above the ground.
            double lift = entity instanceof ItemEntity ? 0.2 : entity.getEyeHeight() * 0.75;
            offer((float) dx, (float) (dy + lift), (float) dz, light, distSq);
        }
        // The blocks that emit light, offered the same way and after the
        // moving sources — so where the list is full, the nearest survive
        // whichever kind they are.
        //
        // Only when traced block light asks for them, as on 1.12.2. Vanilla
        // lights these blocks already, correctly and completely flatly; what
        // putting them here buys is that they can be traced, and a room lit by
        // a torch on one wall stops looking exactly like a room lit by a torch
        // on the other.
        int blockRadius = VulkanConfig.get("tracedBlockLight") > 0
                ? VulkanConfig.get("blockLightRadius") : 0;
        BlockLightSources.update(viewX, viewY, viewZ, blockRadius);
        float[] blocks = BlockLightSources.values();
        for (int i = 0; i < BlockLightSources.count(); i++) {
            int base = i * 4;
            float bx = blocks[base];
            float by = blocks[base + 1];
            float bz = blocks[base + 2];
            offer(bx, by, bz, (int) blocks[base + 3], bx * bx + by * by + bz * bz);
        }
        gathered += count;
    }

    /**
     * Puts one source in the list, dropping the farthest when it is full.
     *
     * Taking whichever happens to come first would make lights wink in and out
     * as the world's own lists are reordered; the nearest are what a surface
     * can actually see.
     */
    private static void offer(float dx, float dy, float dz, int level, double distSq) {
        int slot;
        if (count < MAX_LIGHTS) {
            slot = count++;
        } else {
            int farthestIndex = -1;
            double farthestSq = -1.0;
            for (int j = 0; j < count; j++) {
                double d = distanceSqOf(j);
                if (d > farthestSq) {
                    farthestSq = d;
                    farthestIndex = j;
                }
            }
            if (distSq >= farthestSq) {
                return;
            }
            slot = farthestIndex;
        }
        int base = slot * 4;
        LIGHTS[base] = dx;
        LIGHTS[base + 1] = dy;
        LIGHTS[base + 2] = dz;
        LIGHTS[base + 3] = level;
    }

    /** Squared distance from the camera of a source already in the list. */
    private static double distanceSqOf(int index) {
        int base = index * 4;
        double x = LIGHTS[base];
        double y = LIGHTS[base + 1];
        double z = LIGHTS[base + 2];
        return x * x + y * y + z * z;
    }

    /**
     * The block light level a point receives from the collected sources, 0-15.
     *
     * The same falloff the shader applies, so a mob standing in the pool of
     * light from a dropped torch is lit to match the ground it stands on.
     */
    public static int levelAt(double x, double y, double z) {
        if (count == 0) {
            return 0;
        }
        float best = 0.0f;
        double dx0 = x - originX;
        double dy0 = y - originY;
        double dz0 = z - originZ;
        for (int i = 0; i < count; i++) {
            int base = i * 4;
            double dx = LIGHTS[base] - dx0;
            double dy = LIGHTS[base + 1] - dy0;
            double dz = LIGHTS[base + 2] - dz0;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float level = (float) (LIGHTS[base + 3] - distance);
            if (level > best) {
                best = level;
            }
        }
        return best <= 0.0f ? 0 : (int) Math.min(15.0f, best);
    }

    /**
     * Raises the block-light half of a packed light map coordinate to account
     * for the collected sources.
     *
     * The game packs these as {@code sky << 20 | block << 4} ({@code
     * LightTexture.pack}), and hands the same shape out to entities, to the
     * first-person hand and to particles. Each of those is a separate hook, and
     * this is what they share, so the bit arithmetic lives here once.
     *
     * @return the coordinate unchanged when nothing here is brighter
     */
    public static int applyTo(int packed, double x, double y, double z) {
        if (count == 0 || !VulkanConfig.on("dynamicLights")) {
            return packed;
        }
        int dynamic = levelAt(x, y, z);
        if (dynamic <= 0) {
            return packed;
        }
        int block = (packed >> 4) & 0xF;
        return dynamic <= block ? packed : (packed & ~0xF0) | (dynamic << 4);
    }

    /**
     * Counts what the entity hook did, where it happens, so the report can say
     * whether it ran and whether it ever changed anything. An injection that
     * found nothing and one that ran and raised nothing look identical in the
     * picture; on 1.12.2 that difference hid a dark held torch for a release.
     */
    public static void recordEntityLight(int before, int after) {
        entityAsked++;
        if (after != before) {
            entityRaised++;
        }
    }

    /** 0 when the entity emits nothing. */
    private static int lightLevel(Entity entity) {
        if (entity.isOnFire()) {
            return 15;
        }
        // A creeper lit with flint and steel is not on fire — it is primed, and
        // isOnFire() is false for it. It flashes white and is about to explode,
        // which reads as a light source to anyone looking at it.
        //
        // isIgnited(), not getSwellDir(). The latter is the swell, which vanilla
        // switches on and off as the player moves in and out of range, so a
        // light keyed to it blinks. Ignition is set once, by the flint and
        // steel, and stays set.
        if (entity instanceof CreeperEntity && ((CreeperEntity) entity).isIgnited()) {
            return 10;
        }
        if (entity instanceof ItemEntity) {
            return stackLight(((ItemEntity) entity).getItem());
        }
        if (entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) entity;
            return Math.max(stackLight(living.getMainHandItem()),
                    stackLight(living.getOffhandItem()));
        }
        return 0;
    }

    /**
     * How much light the block form of an item gives off.
     *
     * Deliberately the block's own value rather than a table of special cases:
     * a modded glowing block carried in the hand then lights the way without
     * this having to know it exists.
     */
    private static int stackLight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        // Two things that plainly glow and are not blocks, so asking for their
        // block form returns air. Everything that has a block form is still
        // answered by the block itself, modded or not.
        if (item == Items.LAVA_BUCKET) {
            return 15;
        }
        if (item == Items.BLAZE_ROD) {
            return 10;
        }
        Block block = Block.byItem(item);
        if (block == null || block == Blocks.AIR) {
            return 0;
        }
        try {
            return block.defaultBlockState().getLightEmission();
        } catch (Throwable t) {
            // A block that cannot describe its own default state is not worth
            // failing a frame over.
            return 0;
        }
    }

    /** Read and reset, for the log. */
    public static String stats() {
        if (!VulkanConfig.on("dynamicLights")) {
            return "dynamic lights: off";
        }
        if (frames == 0) {
            // The sources are gathered inside the Vulkan terrain draw, so with
            // no Vulkan draw this would say "no frames yet" forever.
            return "dynamic lights: on and gathering nothing — the sources are collected "
                    + "inside the Vulkan terrain draw, and there was none";
        }
        // The level at the camera itself is what a carried torch produces, and
        // it is the number to look at when the hand is not being lit: if it is
        // high here and the hand is still dark, the light is being found and
        // something downstream is dropping it.
        String line = String.format(
                "dynamic lights: %.1f sources per frame from %.0f entities scanned over %d frames, "
                        + "level at camera %d; entity light asked %d, raised %d",
                gathered / (double) frames, scanned / (double) frames, frames,
                levelAt(originX, originY, originZ), entityAsked, entityRaised);
        gathered = 0L;
        scanned = 0L;
        frames = 0L;
        entityAsked = 0L;
        entityRaised = 0L;
        return line;
    }
}
