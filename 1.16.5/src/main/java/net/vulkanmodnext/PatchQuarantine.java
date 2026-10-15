package net.vulkanmodnext;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Keeps a patch that collides with another mod from stopping every launch.
 *
 * A mixin that cannot be applied — another mod rewrote the same method, or
 * removed the line it hooks — fails the launch, and without this it fails
 * every launch after it too, with nothing the player can do but remove the
 * mod. Here the failure is written down against the patch's group, and the
 * next launch leaves that group out. The launch that found it still fails;
 * applying the rest of a class whose patch broke halfway is not safer than
 * starting again, which is the same choice the 1.12.2 build makes.
 *
 * A failure in the core group, which the renderer cannot run without, takes
 * every group out: the mod then does nothing at all, and the game plays as if
 * it were not installed rather than not starting.
 *
 * The record is a plain file, {@code config/vulkanmodnext-patches.properties}.
 * Deleting it, or a line of it, tries the patches again.
 */
public final class PatchQuarantine implements IMixinErrorHandler {

    private static final String FILE_NAME = "vulkanmodnext-patches.properties";
    private static final String OUR_PACKAGE = "net.vulkanmodnext.mixin.";
    public static final String CORE = "core";
    /** Every group stands down when this key is present. */
    private static final String EVERYTHING = "everything";

    private static final Map<String, String> GROUP_OF = new HashMap<>();

    static {
        group(CORE, "BufferBuilderAccess", "ChunkBufferLayerMixin", "FrameEndMixin",
                "ChunkUploadMixin", "ChunkRenderAccess", "LightTextureAccess", "StartupMixin",
                "TerrainDrawMixin", "WorldRendererAccess", "VertexBufferMixin",
                "VertexBufferUploadMixin",
                // Accessors only add methods, and code in other groups casts to
                // them; leaving one out would turn its group's absence into a
                // ClassCastException somewhere else.
                "ParticleManagerAccess", "ModelPartAccess", "RenderTypeAccess",
                "RenderTypeStateAccess", "TextureStateAccess", "RenderStateAccess");
        group("materials", "MaterialLayerMixin", "MaterialTagMixin",
                "CompiledChunkSpritesMixin", "ItemSpriteMixin");
        group("animation", "AtlasAnimationMixin", "SpriteUploadMixin", "AnimatedTexturesMixin");
        group("sceneEffects", "SceneEffectsMixin");
        group("sky", "SkySunMixin", "CloudTintMixin");
        group("lights", "EntityLightMixin", "ParticleLightMixin");
        group("particles", "ParticleRenderMixin", "ExplosionParticleMixin");
        group("weather", "WeatherRenderMixin");
        group("worldDisplay", "WeatherDisplayMixin", "WorldTimeDisplayMixin");
        group("distances", "EntityRenderDistanceMixin", "TileEntityRenderDistanceMixin",
                "BackgroundThrottleMixin", "NearPlaneMixin");
        group("chunkBuilding", "ChunkBuildThreadsMixin", "ChunkBuilderExecutorMixin",
                "BuildNearOffThreadMixin", "BuildNearTimingMixin", "ChunkPreloadMixin");
        group("cpuSavings", "FrustumTestMixin", "EntitySectionsMixin");
        group("entities", "EntityPassMixin", "EntityBufferMixin", "ModelPartMixin");
    }

    private static void group(String name, String... mixins) {
        for (String mixin : mixins) {
            GROUP_OF.put(mixin, name);
        }
    }

    private static Properties state;

    /** The group a mixin class belongs to; an unlisted one counts as core. */
    public static String groupOf(String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        String group = GROUP_OF.get(simple);
        return group != null ? group : CORE;
    }

    /** Whether this group's patches are out for this launch. */
    public static synchronized boolean isOff(String group) {
        Properties read = read();
        return read.containsKey(EVERYTHING) || read.containsKey(group);
    }

    /** Whether the renderer stood down entirely after a failure in its core. */
    public static synchronized boolean everythingOff() {
        return read().containsKey(EVERYTHING);
    }

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin,
                                      ErrorAction action) {
        record(mixin, th);
        return action;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin,
                                    ErrorAction action) {
        record(mixin, th);
        return action;
    }

    private static void record(IMixinInfo mixin, Throwable th) {
        try {
            String name = mixin == null ? null : mixin.getClassName();
            if (name == null || !name.startsWith(OUR_PACKAGE)) {
                return;
            }
            String group = groupOf(name);
            String reason = name.substring(OUR_PACKAGE.length()) + ": " + firstLine(th);
            synchronized (PatchQuarantine.class) {
                read().setProperty(CORE.equals(group) ? EVERYTHING : group, reason);
                write();
            }
            System.out.println("[VulkanModNext] " + reason);
            System.out.println("[VulkanModNext] " + (CORE.equals(group)
                    ? "A patch the renderer cannot run without collides with another mod. "
                            + "Start the game again and VulkanMod Next will stand aside entirely."
                    : "The '" + group + "' patches collide with another mod. Start the game "
                            + "again and it will load without them.")
                    + " Delete config/" + FILE_NAME + " to try again, and please report this "
                    + "with the whole of logs/latest.log.");
        } catch (Throwable failure) {
            System.out.println("[VulkanModNext] Could not record the failed patch. " + failure);
        }
    }

    private static String firstLine(Throwable th) {
        if (th == null) {
            return "unknown failure";
        }
        String message = th.getMessage();
        if (message == null || message.isEmpty()) {
            return th.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static File file() {
        File config;
        try {
            config = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().toFile();
        } catch (Throwable notYet) {
            config = new File("config");
        }
        return new File(config, FILE_NAME);
    }

    private static Properties read() {
        if (state != null) {
            return state;
        }
        Properties loaded = new Properties();
        File file = file();
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                loaded.load(in);
            } catch (IOException e) {
                System.out.println("[VulkanModNext] Could not read " + FILE_NAME
                        + "; every patch stays in. " + e);
            }
        }
        state = loaded;
        return state;
    }

    private static void write() {
        File file = file();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            read().store(out, "VulkanMod Next patches that collided with another mod and are "
                    + "left out. Delete a line, or the file, to try them again.");
        } catch (IOException e) {
            System.out.println("[VulkanModNext] Could not write " + FILE_NAME + ". " + e);
        }
    }
}
