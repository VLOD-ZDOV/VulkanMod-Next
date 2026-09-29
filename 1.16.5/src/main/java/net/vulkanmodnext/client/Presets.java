package net.vulkanmodnext.client;

import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.AmbientOcclusionStatus;
import net.minecraft.client.settings.CloudOption;
import net.minecraft.client.settings.GraphicsFanciness;
import net.minecraft.client.settings.ParticleStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One click from "I installed it" to a picture worth installing it for.
 *
 * <h2>The same numbers as 1.12.2</h2>
 *
 * Every value here is the 1.12.2 build's {@code VulkanPresets}, key for key.
 * They were tuned there against pictures, and a player moving between the two
 * versions should get the same look from the same name. A setting this port
 * does not act on yet is still written: it is remembered, and when the part it
 * steers arrives the preset is already right.
 *
 * <h2>What a preset owns</h2>
 *
 * Everything that can ruin the picture, not only the effects it turns up. On
 * 1.12.2 a fog distance nobody's preset wrote left players with a white wall
 * after picking Beautiful, so every look writes fog, the diagnostic views and
 * the terrain switch as well — a view left on from debugging is the same
 * picture as a broken renderer.
 */
public final class Presets {

    /** A name, one line saying what it is for, and what it does. */
    public static final class Preset {
        public final String name;
        public final String description;
        private final Runnable apply;

        Preset(String name, String description, Runnable apply) {
            this.name = name;
            this.description = description;
            this.apply = apply;
        }

        public void apply() {
            apply.run();
        }
    }

    public static final Preset[] ALL = {
            new Preset("Stable", "Every Vulkan setting back to its default. This is the reset "
                    + "button; the game's own video settings are left alone.",
                    VulkanConfig::reset),
            new Preset("Balanced", "Stable, plus the game's own settings: fancy graphics, smooth "
                    + "lighting, clouds, render distance capped at 32.", Presets::balanced),
            new Preset("Performance", "Fast graphics, no clouds, no smooth lighting, render "
                    + "distance capped at 16. No effects.", Presets::performance),
            new Preset("Potato", "For weak machines: flat block colours, render distance 8, "
                    + "vsync on, 60 fps cap.", Presets::potato),
            new Preset("Beautiful", "Every finished effect at a considered strength. Render "
                    + "distance capped at 12 to pay for them.", Presets::beautiful),
            new Preset("Golden Hour", "Beautiful, warmer: long light, strong haze and god rays, "
                    + "a stronger glow.", Presets::goldenHour),
            new Preset("Cold Front", "Beautiful, cooler: deep shade, wet surfaces, icy shine, "
                    + "little haze.", Presets::coldFront),
            new Preset("Soft Film", "Beautiful, softer: a strong glow and a filmic tone over "
                    + "a hazier world.", Presets::softFilm),
    };

    private Presets() {
    }

    /** What the vanilla half of a preset asks for; -1 leaves it alone. */
    private static final class Vanilla {
        ParticleStatus particles;
        GraphicsFanciness graphics;
        AmbientOcclusionStatus smoothLighting;
        CloudOption clouds;
        boolean entityShadows;
        int renderDistanceCap;
        int fpsLimit;
        boolean vsync;
    }

    private static Map<String, Integer> base(int entityDistance, int tileEntityDistance,
                                             int backgroundFps, boolean animations,
                                             boolean flat, int framesInFlight) {
        Map<String, Integer> look = new LinkedHashMap<>();
        look.put("terrainEnabled", 1);
        look.put("depthBlitEnabled", 1);
        look.put("cullingEnabled", 1);
        look.put("geometryBudgetMiB", 0);
        look.put("entityDistance", entityDistance);
        look.put("tileEntityDistance", tileEntityDistance);
        look.put("backgroundFpsLimit", backgroundFps);
        look.put("animatedTextures", animations ? 1 : 0);
        look.put("flatBlockColours", flat ? 1 : 0);
        look.put("framesInFlight", framesInFlight);
        look.put("chunkBuildThreads", coresForChunkBuilding());
        // Every effect off unless a look turns it on: a preset is the whole
        // picture, not the part of it that differs from whatever was there.
        for (String effect : new String[] {"directionalLightStrength", "heightFog",
                "waterReflection", "waterWaves", "foliageSway", "bloom", "screenReflections",
                "ambientOcclusion", "sceneTone", "celestialGlint", "iceShine", "waterCaustics",
                "wetSurfaces", "sunHaze", "cloudTint", "skyGradient", "leafShadows", "leafGlow",
                "contactShadows", "creatureLight", "cloudShadows", "godRays",
                "waterRefraction"}) {
            look.put(effect, 0);
        }
        look.put("directionalLightStrength", fallback("directionalLightStrength"));
        look.put("heightFogDepth", fallback("heightFogDepth"));
        look.put("aoRadius", fallback("aoRadius"));
        look.put("sceneWarmth", fallback("sceneWarmth"));
        look.put("exposure", 50);
        look.put("sceneGamma", 50);
        look.put("materialTags", 0);
        look.put("dynamicLights", 0);
        look.put("sceneOcclusion", 0);
        look.put("hdrFrame", 0);
        look.put("roundSun", 0);
        look.put("roundMoon", 0);
        look.put("fogDistance", fallback("fogDistance"));
        look.put("fog", 1);
        look.put("smartAnimations", 0);
        return look;
    }

    private static Map<String, Integer> showcase() {
        Map<String, Integer> look = base(256, 128, 10, true, false, 3);
        look.put("vulkanEntities", 1);
        look.put("materialTags", 1);
        look.put("dynamicLights", 1);
        look.put("roundSun", 1);
        look.put("roundMoon", 1);
        look.put("leafShadows", 100);
        look.put("hdrFrame", 1);
        look.put("sceneOcclusion", 1);
        return look;
    }

    private static Vanilla showcaseVanilla() {
        Vanilla v = new Vanilla();
        v.particles = ParticleStatus.ALL;
        v.graphics = GraphicsFanciness.FANCY;
        v.smoothLighting = AmbientOcclusionStatus.MAX;
        v.clouds = CloudOption.FANCY;
        v.entityShadows = true;
        v.renderDistanceCap = 12;
        v.fpsLimit = 260;
        v.vsync = false;
        return v;
    }

    private static void beautiful() {
        Map<String, Integer> look = showcase();
        put(look, "directionalLightStrength", 65, "heightFog", 20, "waterReflection", 70,
                "waterWaves", 50, "foliageSway", 38, "bloom", 45, "ambientOcclusion", 60,
                "sceneTone", 45, "sceneWarmth", 55, "waterRefraction", 45, "celestialGlint", 55,
                "iceShine", 60, "waterCaustics", 60, "wetSurfaces", 65, "sunHaze", 60,
                "cloudTint", 70, "skyGradient", 55, "leafGlow", 55, "contactShadows", 60,
                "creatureLight", 55, "cloudShadows", 45, "godRays", 45);
        apply(look, showcaseVanilla());
    }

    private static void goldenHour() {
        Map<String, Integer> look = showcase();
        put(look, "directionalLightStrength", 70, "heightFog", 35, "waterReflection", 70,
                "waterWaves", 45, "foliageSway", 38, "bloom", 60, "ambientOcclusion", 45,
                "sceneTone", 55, "sceneWarmth", 80, "exposure", 55, "waterRefraction", 45,
                "celestialGlint", 70, "iceShine", 40, "waterCaustics", 55, "wetSurfaces", 0,
                "sunHaze", 85, "cloudTint", 85, "skyGradient", 45, "leafGlow", 80,
                "contactShadows", 70, "creatureLight", 55, "cloudShadows", 50, "godRays", 70);
        apply(look, showcaseVanilla());
    }

    private static void coldFront() {
        Map<String, Integer> look = showcase();
        put(look, "directionalLightStrength", 55, "heightFog", 30, "waterReflection", 75,
                "waterWaves", 55, "foliageSway", 30, "bloom", 25, "ambientOcclusion", 80,
                "aoRadius", 3, "sceneTone", 40, "sceneWarmth", 25, "exposure", 48,
                "waterRefraction", 50, "celestialGlint", 35, "iceShine", 85, "waterCaustics", 40,
                "wetSurfaces", 80, "sunHaze", 15, "cloudTint", 40, "skyGradient", 75,
                "leafGlow", 25, "contactShadows", 75, "creatureLight", 45, "cloudShadows", 60,
                "godRays", 15);
        apply(look, showcaseVanilla());
    }

    private static void softFilm() {
        Map<String, Integer> look = showcase();
        put(look, "directionalLightStrength", 55, "heightFog", 45, "heightFogDepth", 34,
                "waterReflection", 65, "waterWaves", 40, "foliageSway", 34, "bloom", 75,
                "ambientOcclusion", 50, "sceneTone", 70, "sceneWarmth", 60, "exposure", 45,
                "waterRefraction", 40, "celestialGlint", 45, "iceShine", 45, "waterCaustics", 45,
                "wetSurfaces", 40, "sunHaze", 45, "cloudTint", 60, "skyGradient", 50,
                "leafGlow", 60, "contactShadows", 45, "creatureLight", 60, "cloudShadows", 40,
                "godRays", 55);
        apply(look, showcaseVanilla());
    }

    private static void balanced() {
        Map<String, Integer> look = base(128, 64, 10, true, false, 2);
        look.put("vulkanEntities", 1);
        Vanilla v = new Vanilla();
        v.particles = ParticleStatus.DECREASED;
        v.graphics = GraphicsFanciness.FANCY;
        v.smoothLighting = AmbientOcclusionStatus.MAX;
        v.clouds = CloudOption.FANCY;
        v.entityShadows = true;
        v.renderDistanceCap = 32;
        v.fpsLimit = 260;
        v.vsync = false;
        apply(look, v);
    }

    private static void performance() {
        Map<String, Integer> look = base(64, 32, 5, false, false, 2);
        look.put("vulkanEntities", 0);
        Vanilla v = new Vanilla();
        v.particles = ParticleStatus.MINIMAL;
        v.graphics = GraphicsFanciness.FAST;
        v.smoothLighting = AmbientOcclusionStatus.OFF;
        v.clouds = CloudOption.OFF;
        v.entityShadows = false;
        v.renderDistanceCap = 16;
        v.fpsLimit = 260;
        v.vsync = false;
        apply(look, v);
    }

    private static void potato() {
        Map<String, Integer> look = base(32, 16, 1, false, true, 2);
        look.put("vulkanEntities", 0);
        look.put("geometryBudgetMiB", 256);
        look.put("chunkBuildThreads", Math.max(1, coresForChunkBuilding() - 1));
        Vanilla v = new Vanilla();
        v.particles = ParticleStatus.MINIMAL;
        v.graphics = GraphicsFanciness.FAST;
        v.smoothLighting = AmbientOcclusionStatus.OFF;
        v.clouds = CloudOption.OFF;
        v.entityShadows = false;
        v.renderDistanceCap = 8;
        v.fpsLimit = 60;
        v.vsync = true;
        apply(look, v);
    }

    private static void put(Map<String, Integer> look, Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            look.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
    }

    private static void apply(Map<String, Integer> look, Vanilla v) {
        // Diagnostic views off: a view left on from debugging is the same
        // picture as a broken renderer, and a preset is the button a player
        // presses when the picture looks broken.
        for (Settings.Setting setting : Settings.all()) {
            if (setting.key.startsWith("show")) {
                look.put(setting.key, 0);
            }
        }
        VulkanConfig.setAll(look);

        Minecraft mc = Minecraft.getInstance();
        GameSettings settings = mc.options;
        GraphicsFanciness wasGraphics = settings.graphicsMode;
        AmbientOcclusionStatus wasSmooth = settings.ambientOcclusion;
        int wasDistance = settings.renderDistance;

        settings.particles = v.particles;
        // Never down from Fabulous to Fancy by a preset that only asked for
        // fancy: Fabulous is a choice somebody made on purpose.
        if (!(v.graphics == GraphicsFanciness.FANCY
                && settings.graphicsMode == GraphicsFanciness.FABULOUS)) {
            settings.graphicsMode = v.graphics;
        }
        settings.ambientOcclusion = v.smoothLighting;
        settings.renderClouds = v.clouds;
        settings.entityShadows = v.entityShadows;
        settings.framerateLimit = v.fpsLimit;
        mc.getWindow().setFramerateLimit(v.fpsLimit);
        if (settings.enableVsync != v.vsync) {
            settings.enableVsync = v.vsync;
            mc.getWindow().updateVsync(v.vsync);
        }
        if (settings.renderDistance > v.renderDistanceCap) {
            settings.renderDistance = v.renderDistanceCap;
        }
        if (!Flight.asked()) {
            settings.save();
        }

        if (mc.levelRenderer != null && mc.level != null
                && (settings.graphicsMode != wasGraphics || settings.ambientOcclusion != wasSmooth
                    || settings.renderDistance != wasDistance)) {
            mc.levelRenderer.allChanged();
        }
    }

    private static int fallback(String key) {
        for (Settings.Setting setting : Settings.all()) {
            if (setting.key.equals(key)) {
                return setting.fallback;
            }
        }
        return 0;
    }

    /** The same answer as 1.12.2 gives: every core the machine has. */
    static int coresForChunkBuilding() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}
