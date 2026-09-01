package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.vulkanmodnext.VulkanModNext;

/**
 * Opens a world by itself, so that the port can be checked without a person
 * clicking through the menus.
 *
 * <h2>Why it is worth the code</h2>
 *
 * Nothing interesting happens in this mod until sections are being built, and
 * sections are only built in a world. Every check from here on — the shape of a
 * vertex, the mirror, the terrain draw, and every measurement after that —
 * needs one open. A person opening it by hand also opens a different one each
 * time, and this project has the scar for that: two builds of the same code
 * measured forty percent apart because the world was not the same world.
 *
 * <p>So the seed is fixed and named, and the save is reused once it exists.
 * The first run of a seed generates terrain and is warm-up, not a measurement —
 * that rule is inherited from the 1.12.2 side and it cost a day to learn.
 *
 * <p>Switched on with {@code -Pworld=<name>} at the dev client, and does
 * nothing at all otherwise. It is not shipped behaviour: a mod that opens a
 * world on its own would be a bug in anybody's hands but ours.
 */
public final class DevWorld {

    /** Fixed, so two runs are two runs of the same world. */
    private static final long SEED = 777L;

    private static boolean done;

    private DevWorld() {
    }

    public static void openIfAsked() {
        String name = System.getProperty("vulkanmodnext.world");
        if (done || name == null || name.isEmpty()) {
            return;
        }
        done = true;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.getLevelSource().levelExists(name)) {
                VulkanModNext.LOGGER.info("Dev world: opening the existing '{}'", name);
                mc.createWorldOpenFlows().openWorld(name, () -> { });
                return;
            }
            VulkanModNext.LOGGER.info("Dev world: creating '{}' with seed {} — this run generates "
                    + "terrain and is warm-up, not a measurement", name, SEED);
            // Creative and peaceful: nothing should be able to kill the camera
            // in the middle of a measurement.
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(FeatureFlags.DEFAULT_FLAGS),
                    WorldDataConfiguration.DEFAULT);
            WorldOptions options = new WorldOptions(SEED, true, false);
            mc.createWorldOpenFlows().createFreshLevel(name, settings, options,
                    WorldPresets::createNormalWorldDimensions, mc.screen);
        } catch (Throwable failed) {
            VulkanModNext.LOGGER.error("Dev world: could not open '{}'", name, failed);
        }
    }
}
