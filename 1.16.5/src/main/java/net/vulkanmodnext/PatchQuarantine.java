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
 * Two ways a patch fails, and they are caught in different places:
 *
 * - its hook finds nowhere to go, because another mod rewrote or removed the
 *   line it attaches to. Mixin would throw an error past every handler here,
 *   so injectors are not required ({@code defaultRequire: 0}) and
 *   {@link #checkApplied} counts afterwards whether each one landed. A hook
 *   that did not is written down and the game carries on without it;
 * - the patch itself cannot be applied (a missing field or class). Mixin
 *   reports those to {@link IMixinErrorHandler}, the launch still fails, and
 *   the record keeps the next one from failing the same way.
 *
 * Either way the next launch leaves that group out.
 *
 * A failure in the core group, which the renderer cannot run without, takes
 * every group out: the mod then does nothing at all, and the game plays as if
 * it were not installed rather than not starting.
 *
 * The record is a plain file, {@code config/vulkanmodnext-patches.properties}.
 * Deleting it, or a line of it, tries the patches again; so does installing a
 * different version of the mod, which clears it, because a new version may be
 * exactly the fix for the collision it records.
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

    /** Version key in the record; a record from another version is dropped. */
    private static final String VERSION_KEY = "version";

    /**
     * Set when a core hook missed during this launch. The renderer cannot be
     * trusted with the world then — it may be copying chunks it is never
     * asked to draw — so the terrain stays with the game until the next
     * launch, which leaves the whole mod out.
     */
    private static volatile boolean coreMissedThisLaunch;

    public static boolean coreMissedThisLaunch() {
        return coreMissedThisLaunch;
    }

    private static final String[] INJECTORS = {
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
    };

    /**
     * After a mixin is applied: did each of its hooks land somewhere?
     *
     * A handler that hooked is called from the target class, under its own
     * name with a prefix Mixin adds; one that found nothing to hook is merged
     * in and never called. So a handler with no call to it is a hook that
     * missed.
     */
    public static void checkApplied(org.objectweb.asm.tree.ClassNode target,
                                    org.spongepowered.asm.mixin.extensibility.IMixinInfo mixin) {
        try {
            org.objectweb.asm.tree.ClassNode node = mixin.getClassNode(0);
            java.util.Set<String> called = new java.util.HashSet<>();
            for (org.objectweb.asm.tree.MethodNode method : target.methods) {
                for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst();
                        insn != null; insn = insn.getNext()) {
                    if (insn instanceof org.objectweb.asm.tree.MethodInsnNode
                            && ((org.objectweb.asm.tree.MethodInsnNode) insn).owner
                                    .equals(target.name)) {
                        called.add(((org.objectweb.asm.tree.MethodInsnNode) insn).name);
                    }
                }
            }
            for (org.objectweb.asm.tree.MethodNode handler : node.methods) {
                if (!isInjector(handler)) {
                    continue;
                }
                boolean landed = false;
                for (String name : called) {
                    if (name.equals(handler.name) || name.endsWith("$" + handler.name)) {
                        landed = true;
                        break;
                    }
                }
                if (!landed) {
                    missed(mixin.getClassName(), handler.name + " found nothing to hook in "
                            + target.name.replace('/', '.'));
                }
            }
        } catch (Throwable failure) {
            System.out.println("[VulkanModNext] Could not check " + mixin.getClassName()
                    + ". " + failure);
        }
    }

    private static boolean isInjector(org.objectweb.asm.tree.MethodNode method) {
        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> list : java.util.Arrays.asList(
                method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (org.objectweb.asm.tree.AnnotationNode annotation : list) {
                for (String injector : INJECTORS) {
                    if (injector.equals(annotation.desc)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void missed(String mixinClassName, String why) {
        if (CORE.equals(groupOf(mixinClassName))) {
            coreMissedThisLaunch = true;
        }
        recordFailure(mixinClassName, why, false);
    }

    /** The group a mixin class belongs to; an unlisted one counts as core. */
    public static String groupOf(String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        String group = GROUP_OF.get(simple);
        return group != null ? group : CORE;
    }

    /** Whether this group's patches are out for this launch. */
    public static synchronized boolean isOff(String group) {
        Properties read = read();
        return read.containsKey(EVERYTHING) || (!VERSION_KEY.equals(group) && read.containsKey(group));
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
        String name = mixin == null ? null : mixin.getClassName();
        if (name != null && name.startsWith(OUR_PACKAGE)) {
            recordFailure(name, firstLine(th), true);
        }
    }

    private static void recordFailure(String name, String why, boolean launchFails) {
        try {
            String group = groupOf(name);
            String reason = name.substring(name.lastIndexOf('.') + 1) + ": " + why;
            synchronized (PatchQuarantine.class) {
                read().setProperty(CORE.equals(group) ? EVERYTHING : group, reason);
                write();
            }
            System.out.println("[VulkanModNext] " + reason);
            String what = CORE.equals(group)
                    ? "A patch the renderer cannot run without collides with another mod."
                    : "The '" + group + "' patches collide with another mod.";
            String next = CORE.equals(group)
                    ? " From the next launch VulkanMod Next stands aside entirely and the game "
                            + "draws as if it were not installed."
                    : " From the next launch it loads without them.";
            System.out.println("[VulkanModNext] " + what
                    + (launchFails ? " This launch cannot continue." : "") + next
                    + " Recorded in config/" + FILE_NAME + "; delete it to try again (a new "
                    + "version of the mod clears it by itself). Please report this with the "
                    + "whole of logs/latest.log.");
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
        String current = PatchQuarantine.class.getPackage() == null ? null
                : PatchQuarantine.class.getPackage().getImplementationVersion();
        if (current != null && !current.equals(loaded.getProperty(VERSION_KEY))) {
            // Another version wrote this, or nobody has yet. What it records
            // was a collision with that version's patches, so it is dropped;
            // if the collision is still there it is found again.
            boolean hadEntries = false;
            for (Object key : loaded.keySet()) {
                hadEntries |= !VERSION_KEY.equals(key);
            }
            loaded.clear();
            loaded.setProperty(VERSION_KEY, current);
            state = loaded;
            if (hadEntries) {
                System.out.println("[VulkanModNext] A new version: patches left out by the "
                        + "previous one are tried again.");
                write();
            }
            return state;
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
