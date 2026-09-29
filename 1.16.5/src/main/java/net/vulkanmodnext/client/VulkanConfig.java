package net.vulkanmodnext.client;

import net.vulkanmodnext.VulkanModNext;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * What the settings currently say, and where they are kept.
 *
 * <h2>Everything is an int</h2>
 *
 * Including the switches, which are 0 or 1. One store instead of two, and the
 * screen does not have to ask which kind a row is before reading it — the
 * {@link Settings} table already knows. What is lost is nothing: no setting in
 * this mod has ever been anything but a switch or a whole number.
 *
 * <h2>Values are kept even for settings that do nothing</h2>
 *
 * Most of these steer a part of the mod that has not been ported yet. They are
 * still stored and still written to the file, so that when the part arrives the
 * player's choice is already there — and so that a config file carried from one
 * build to the next does not quietly lose half its lines.
 */
public final class VulkanConfig {

    private static final Map<String, Integer> VALUES = new ConcurrentHashMap<>();
    private static File file;

    private VulkanConfig() {
    }

    public static synchronized void load(File configDirectory) {
        file = new File(configDirectory, "vulkanmodnext.properties");
        takeCommandLine();
        for (Settings.Setting setting : Settings.all()) {
            VALUES.put(setting.key, setting.fallback);
        }
        if (!file.exists()) {
            save();
            publish();
            return;
        }
        Properties stored = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            stored.load(in);
        } catch (IOException failed) {
            VulkanModNext.LOGGER.warn("Could not read {}, using defaults: {}",
                    file.getName(), failed.toString());
            // Sent even so. The defaults are already in the map above, and the
            // renderer reads settings only as system properties — so leaving
            // without this is not "fall back to defaults", it is a renderer
            // that never hears a single setting because a file could not be
            // opened.
            publish();
            return;
        }
        int read = 0;
        for (Settings.Setting setting : Settings.all()) {
            String value = stored.getProperty(setting.key);
            if (value == null) {
                continue;
            }
            try {
                VALUES.put(setting.key, clamp(setting, Integer.parseInt(value.trim())));
                read++;
            } catch (NumberFormatException ignored) {
                // A line somebody edited by hand into nonsense is not worth
                // refusing to start over; the default stands and the count says
                // one fewer was read.
            }
        }
        VulkanModNext.LOGGER.info("Settings: {} of {} read from {}",
                read, Settings.all().size(), file.getName());
        publish();
    }

    public static synchronized void save() {
        if (file == null) {
            return;
        }
        Properties out = new Properties();
        for (Settings.Setting setting : Settings.all()) {
            out.setProperty(setting.key, Integer.toString(get(setting.key)));
        }
        try (FileOutputStream stream = new FileOutputStream(file)) {
            out.store(stream, "VulkanMod 1.16.5 — see the in-game settings screen");
        } catch (IOException failed) {
            VulkanModNext.LOGGER.warn("Could not write {}: {}", file.getName(), failed.toString());
        }
    }

    private static int clamp(Settings.Setting setting, int value) {
        return Math.max(setting.min, Math.min(setting.max, value));
    }

    /**
     * Names the Vulkan half reads, and the setting each one comes from.
     *
     * <h2>Why through system properties</h2>
     *
     * The Vulkan half was written on 1.12.2, where it lives on a class loader
     * of its own and cannot see the game's classes at all — so the only thing
     * both halves could agree on was a system property. Here they are in one
     * loader and could talk directly, but keeping the same channel means those
     * eighteen thousand lines arrive needing no edits, and this table is the
     * whole cost of that.
     *
     * <p>Only the ones it actually reads are here. A property nothing reads is
     * a check with no consumer, and this project has a rule about those.
     */
    private static final String[][] PUBLISHED = {
            {"compactVertices", "vulkanmodnext.compactVertices"},
            {"groupQuadFacings", "vulkanmodnext.groupFacings"},
            {"cullingEnabled", "vulkanmodnext.cull"},
            {"depthBlitEnabled", "vulkanmodnext.depthBlit"},
            {"flatBlockColours", "vulkanmodnext.flatBlockColours"},
            {"motionOverWorld", "vulkanmodnext.motionOverWorld"},
            {"hdrFrame", "vulkanmodnext.hdrFrame"},
            {"sceneOcclusion", "vulkanmodnext.sceneOcclusion"},
            {"showAccumulation", "vulkanmodnext.showAccumulation"},
            {"showCreatureLight", "vulkanmodnext.showCreatureLight"},
            {"showMaterials", "vulkanmodnext.showMaterials"},
            {"showMotion", "vulkanmodnext.showMotion"},
            {"showOcclusion", "vulkanmodnext.showOcclusion"},
            {"showReflections", "vulkanmodnext.showReflections"},
            {"framesInFlight", "vulkanmodnext.framesInFlight"},
            {"geometryBudgetMiB", "vulkanmodnext.geometryBudget"},
            {"vulkanDevice", "vulkanmodnext.vulkanDevice"},
            {"atlasPixelsSeen", "vulkanmodnext.atlasPixelsSeen"},

            // Everything below steers the terrain shader, which is already here:
            // the Vulkan half was ported whole and reads all of these, and the
            // game half simply never sent them. Found by asking the two halves
            // rather than by reading either — see tools/parity.py.
            //
            // Sent is not the same as working. A property that reaches a shader
            // which then ignores it is exactly the check with no consumer this
            // table's comment warns about, so none of these is marked live in
            // Settings until a picture proves it moved.
            {"ambientOcclusion", "vulkanmodnext.ambientOcclusion"},
            {"aoRadius", "vulkanmodnext.aoRadius"},
            {"bloom", "vulkanmodnext.bloom"},
            {"celestialGlint", "vulkanmodnext.celestialGlint"},
            {"cloudShadows", "vulkanmodnext.cloudShadows"},
            {"colourVision", "vulkanmodnext.colourVision"},
            {"contactShadows", "vulkanmodnext.contactShadows"},
            {"creatureLight", "vulkanmodnext.creatureLight"},
            {"exposure", "vulkanmodnext.exposure"},
            {"foliageSway", "vulkanmodnext.foliageSway"},
            {"godRays", "vulkanmodnext.godRays"},
            {"heightFog", "vulkanmodnext.heightFog"},
            {"heightFogDepth", "vulkanmodnext.heightFogDepth"},
            {"iceShine", "vulkanmodnext.iceShine"},
            {"leafGlow", "vulkanmodnext.leafGlow"},
            {"leafShadows", "vulkanmodnext.leafShadows"},
            {"lightSoftness", "vulkanmodnext.lightSoftness"},
            {"sceneGamma", "vulkanmodnext.sceneGamma"},
            {"sceneTone", "vulkanmodnext.sceneTone"},
            {"sceneWarmth", "vulkanmodnext.sceneWarmth"},
            {"screenReflections", "vulkanmodnext.screenReflections"},
            {"shadowSoftness", "vulkanmodnext.shadowSoftness"},
            {"skyGradient", "vulkanmodnext.skyGradient"},
            {"sunHaze", "vulkanmodnext.sunHaze"},
            {"sunShadows", "vulkanmodnext.sunShadows"},
            {"temporalAccumulation", "vulkanmodnext.temporalAccumulation"},
            {"tracedBlockLight", "vulkanmodnext.tracedBlockLight"},
            {"tracedLights", "vulkanmodnext.tracedLights"},
            {"waterCaustics", "vulkanmodnext.waterCaustics"},
            {"waterReflection", "vulkanmodnext.waterReflection"},
            {"waterRefraction", "vulkanmodnext.waterRefraction"},
            {"waterWaves", "vulkanmodnext.waterWaves"},
            {"wetSurfaces", "vulkanmodnext.wetSurfaces"},
    };

    /**
     * Hands the current values to the Vulkan half.
     *
     * Several of these are read once, when a resource is created — the vertex
     * layout is settled before the first chunk and the frame count before the
     * first frame — so this has to run before Vulkan starts, not merely before
     * it draws.
     */
    private static boolean published;

    public static void publish() {
        for (String[] pair : PUBLISHED) {
            Settings.Setting setting = find(pair[0]);
            if (setting == null) {
                continue;
            }
            if (!published && System.getProperty(pair[1]) != null) {
                // Set on the command line before we ever ran. A launcher flag
                // is a deliberate act and outranks the config file — the same
                // rule the scratch stack follows, and the reason a diagnostic
                // run can be made without editing somebody's settings.
                //
                // Remembered rather than decided again, because this method now
                // runs every time a slider moves: asking "was it already set"
                // on the second call would see the value this method itself
                // wrote and hand the launcher flag back to the config file.
                fromCommandLine.add(pair[0]);
                continue;
            }
            if (fromCommandLine.contains(pair[0])) {
                continue;
            }
            int value = get(pair[0]);
            System.setProperty(pair[1], setting.bool
                    ? Boolean.toString(value != 0) : Integer.toString(value));
        }
        published = true;
        // One number the renderer can look at instead of the fifty above it.
        // It reads them all when this moves and skips them when it has not,
        // which is what makes publishing on every slider move affordable.
        System.setProperty("vulkanmodnext.settingsVersion",
                Long.toString(SETTINGS_VERSION.incrementAndGet()));
    }

    /** Keys a launcher flag owns, so the config file never takes them back. */
    private static final java.util.Set<String> fromCommandLine =
            new java.util.HashSet<>();

    private static final java.util.concurrent.atomic.AtomicLong SETTINGS_VERSION =
            new java.util.concurrent.atomic.AtomicLong();

    static {
    }

    private static Settings.Setting find(String key) {
        for (Settings.Setting setting : Settings.all()) {
            if (setting.key.equals(key)) {
                return setting;
            }
        }
        return null;
    }

    /**
     * Values given on the command line as {@code -Dvulkanmodnext.<key>}, for
     * this run only.
     *
     * The settings in {@link #PUBLISHED} already honour a launcher flag: the
     * renderer reads the property itself. The ones the game half acts on did
     * not, so {@code -Pset=materialTags=true} put a property nobody read and
     * the run measured the config file. Kept apart from the stored values so a
     * slider moved during such a run does not write the flag into the file.
     */
    private static final Map<String, Integer> COMMAND_LINE = new ConcurrentHashMap<>();

    private static void takeCommandLine() {
        for (Settings.Setting setting : Settings.all()) {
            String given = System.getProperty("vulkanmodnext." + setting.key);
            if (given == null) {
                continue;
            }
            given = given.trim();
            try {
                int value = "true".equalsIgnoreCase(given) ? 1
                        : "false".equalsIgnoreCase(given) ? 0 : Integer.parseInt(given);
                COMMAND_LINE.put(setting.key, clamp(setting, value));
            } catch (NumberFormatException ignored) {
                // Not ours to interpret; the stored value stands.
            }
        }
    }

    public static int get(String key) {
        Integer forced = COMMAND_LINE.get(key);
        if (forced != null) {
            return forced;
        }
        Integer value = VALUES.get(key);
        return value == null ? 0 : value;
    }

    public static boolean on(String key) {
        return get(key) != 0;
    }

    public static void set(Settings.Setting setting, int value) {
        int before = get(setting.key);
        VALUES.put(setting.key, clamp(setting, value));
        if ("materialTags".equals(setting.key) && before != get(setting.key)) {
            rebuildWorld("material tags");
        }
        // The renderer reads settings as system properties, so a value that
        // only reaches this map is a value the picture never sees. Every way
        // of changing a setting goes through here, which is why it is here and
        // not in the screen that called it.
        publish();
    }

    /**
     * Many values at once, told to the renderer once — a preset.
     *
     * Keys this build does not declare are skipped rather than stored: a
     * preset written against 1.12.2's list must not grow the file with names
     * nothing here will ever read back.
     */
    public static void setAll(Map<String, Integer> values) {
        int tagsBefore = get("materialTags");
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            Settings.Setting setting = find(entry.getKey());
            if (setting != null) {
                VALUES.put(setting.key, clamp(setting, entry.getValue()));
            }
        }
        publish();
        save();
        if (tagsBefore != get("materialTags")) {
            rebuildWorld("material tags");
        }
    }

    public static void reset() {
        int tagsBefore = get("materialTags");
        for (Settings.Setting setting : Settings.all()) {
            VALUES.put(setting.key, setting.fallback);
        }
        if (tagsBefore != get("materialTags")) {
            rebuildWorld("material tags");
        }
        // Same reason as in set(): a value that only reaches this map is a
        // value the picture never sees. Written out separately rather than by
        // calling set() in the loop, so the renderer is told once instead of a
        // hundred and six times.
        publish();
    }

    /**
     * Rebuilds every chunk, for a setting whose effect is written into the
     * geometry while a chunk is built.
     *
     * Without it the world is left half done — chunks built before the switch
     * carry nothing, the ones after carry the new answer — and stays that way
     * until each chunk is rebuilt for some other reason. That looks like an
     * effect that works in some places and not others, and sends the search
     * into the effect rather than to the chunk.
     */
    private static void rebuildWorld(String why) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.levelRenderer != null && mc.level != null) {
            VulkanModNext.LOGGER.info("{} changed: rebuilding every chunk", why);
            mc.levelRenderer.allChanged();
        }
    }

    /**
     * The one setting the port acts on today, named here rather than reached
     * for by string in the middle of a frame.
     */
    public static boolean isTerrainEnabled() {
        // The dev client's own override, so a run can draw through Vulkan
        // without writing anything into a player's settings.
        String forced = System.getProperty("vulkanmodnext.terrain");
        if (forced != null) {
            return "on".equalsIgnoreCase(forced) || "true".equalsIgnoreCase(forced);
        }
        return on("terrainEnabled");
    }
}
