package net.vulkanmodnext.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.settings.CloudOption;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.datafix.codec.DatapackCodec;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.settings.DimensionGeneratorSettings;
import net.minecraft.world.server.ServerWorld;
import net.vulkanmodnext.VulkanModNext;
import org.lwjgl.glfw.GLFW;

/**
 * A flight the game makes by itself, so that a measurement does not need
 * somebody to sit and fly it.
 *
 * <h2>Why this exists</h2>
 *
 * Everything this renderer is judged by happens while the camera moves. Half
 * the terrain code does nothing at all while you stand still, an effect keyed
 * to the screen only misbehaves while you turn, and the numbers that matter —
 * the worst frame in a hundred, the chunk that took a tenth of a second to
 * build — appear in flight and nowhere else. A route flown by the game itself
 * is answered as often as it is worth answering, and two runs of the same route
 * are comparable in a way that two hands on a keyboard are not.
 *
 * <p>This is the port of the 1.12.2 harness, and it arrives first on this
 * version on purpose: until it exists there is <em>no</em> way to measure
 * anything here at all, so every claim about the 1.16.5 renderer is a guess.
 *
 * <h2>What it does</h2>
 *
 * With {@code -Dvulkanmodnext.flight=<route>} the client waits for the main
 * menu, makes a world of its own from a fixed seed, waits for the chunks around
 * the spawn to be built, flies the named route, takes screenshots along it and
 * quits. Nothing is pressed and nothing is clicked.
 *
 * <h2>Routes</h2>
 *
 * <ul>
 *   <li>{@code hover} — stand still, look one way. The quietest measurement
 *       there is: on 1.12.2 its noise floor is 0.4% against the frame
 *       counter's 5% on a moving route, so a change smaller than a twentieth
 *       can only be seen here.</li>
 *   <li>{@code spin} — stand still and turn once all the way round, ending at
 *       the angle it started at. The first and last frames are of the same
 *       view, so a difference between them is the fault itself rather than a
 *       matter of opinion.</li>
 *   <li>{@code line} — fly in a straight line at a constant speed, which is
 *       what makes chunks arrive and is therefore where the stutter lives.</li>
 * </ul>
 *
 * <h2>What is not here, and is on 1.12.2</h2>
 *
 * <ul>
 *   <li><b>Presets.</b> This port has no {@code VulkanPresets} — it has no
 *       effects to preset. Asking for one says so rather than flying with the
 *       wrong settings silently.</li>
 *   <li><b>Chunk builder threads.</b> Nothing here sets them.</li>
 *   <li><b>The diagnostics file.</b> There is none on this version, so the
 *       marks that would go in it go in the log.</li>
 * </ul>
 *
 * <p>The hour and the weather are pinned differently and better: on 1.12.2 they
 * are held by this mod's own time control, which this port does not have, so
 * here they are held on the integrated server itself. That works whoever is
 * drawing, which also makes a vanilla control run comparable.
 */
public final class Flight {

    /** Which route, or empty when nothing was asked for. */
    private static final String ROUTE = System.getProperty("vulkanmodnext.flight", "").trim();

    /** A name for this run, so two of them can sit in the same folder. */
    private static final String TAG = System.getProperty("vulkanmodnext.flightTag", "run").trim();

    /**
     * The world's seed. Fixed by default and settable, because a comparison
     * between two builds is only a comparison if both flew over the same
     * ground.
     */
    private static final long SEED = Long.getLong("vulkanmodnext.flightSeed", 8815751L);

    /** Chunks of render distance, pinned rather than taken from the options. */
    private static final int DISTANCE = Integer.getInteger("vulkanmodnext.flightDistance", 12);

    /** How long the flying part lasts. */
    private static final int SECONDS = Integer.getInteger("vulkanmodnext.flightSeconds", 30);

    /**
     * How long to wait after the world appears before the route starts.
     *
     * The first seconds in a world are chunk building and nothing else, and a
     * frame time from them describes the builder rather than the renderer.
     */
    private static final int SETTLE = Integer.getInteger("vulkanmodnext.flightSettle", 20);

    /** How many frames to keep. */
    private static final int SHOTS = Integer.getInteger("vulkanmodnext.flightShots", 8);

    /**
     * Where to fly from, as {@code x,y,z}, when the world's own spawn is not
     * the ground the route wants. Empty means twelve blocks over the spawn.
     */
    private static final String POSITION = System.getProperty("vulkanmodnext.flightPos", "").trim();

    /**
     * Whether to leave the game's debug screen up.
     *
     * Off by default, and that is not tidiness: the debug screen prints a frame
     * rate, and a frame rate is different in every frame — two pictures of the
     * same view would differ in the one place that has nothing to do with what
     * is being compared.
     */
    private static final boolean PROFILER =
            Boolean.parseBoolean(System.getProperty("vulkanmodnext.flightProfiler", "false"));

    /**
     * Which hour to hold the sky at, or -1 to let the world keep its own time.
     *
     * A world saves its clock, so the second run of a route is flown minutes
     * later in the day than the first: the sun has moved, every shadow with it,
     * and a comparison between the two frames is mostly a comparison of the
     * hour. Held by default at mid-morning.
     */
    private static final int HOUR = Integer.getInteger("vulkanmodnext.flightHour", 9);

    /** Weather: 0 the world's own, 1 clear, 2 rain, 3 storm. Clear by default. */
    private static final int WEATHER = Integer.getInteger("vulkanmodnext.flightWeather", 1);

    /**
     * Give up after this long without reaching the world.
     *
     * A run nobody is watching must end. Without this, a world that fails to
     * load leaves a client sitting on a menu until somebody notices, which on
     * an unattended machine is the next morning.
     */
    private static final int PATIENCE = Integer.getInteger("vulkanmodnext.flightPatience", 180);

    /**
     * The window to fly in, as {@code 1280x720}, or empty to leave it alone.
     *
     * Pinning the window pins the one thing every full-screen pass is priced
     * in: pixels. The same route in a window half the size either doubles or it
     * does not, and that is the whole question.
     */
    private static final String WINDOW = System.getProperty("vulkanmodnext.flightWindow", "").trim();

    /**
     * Clouds: 0 off, 1 fast, 2 fancy, or -1 to leave the option alone.
     *
     * Off by default. A cloud drifts whatever else is held still, and between
     * two frames of the same view it is the largest difference in the picture
     * while having nothing to do with anything a route is flown to compare.
     */
    private static final int CLOUDS = Integer.getInteger("vulkanmodnext.flightClouds", 0);

    /** Which way to face, in degrees. */
    private static final int YAW = Integer.getInteger("vulkanmodnext.flightYaw", 45);

    /** How far down to look, in degrees. */
    private static final int PITCH = Integer.getInteger("vulkanmodnext.flightPitch", 12);

    /** Whether to keep each frame twice, on two consecutive frames. */
    private static final boolean PAIRS =
            Boolean.parseBoolean(System.getProperty("vulkanmodnext.flightPairs", "false"));

    /** Named so the run says out loud that this version cannot honour it. */
    private static final String PRESET = System.getProperty("vulkanmodnext.flightPreset", "").trim();

    /**
     * The frame cap to fly under. 260 is what this version calls unlimited.
     *
     * Settable only because a route flown deliberately against a cap is a real
     * experiment — everything else in a frame changes when the card is allowed
     * to idle between frames. The default is no cap at all.
     */
    private static final int FRAMERATE = Integer.getInteger("vulkanmodnext.flightFramerate", 260);

    private static final int TICKS_PER_SECOND = 20;

    /** One reading of the game's own frame counter per second of the route. */
    private static final java.util.List<Integer> frameRates = new java.util.ArrayList<>();

    private enum Stage {
        /** Nothing was asked for. */
        OFF,
        /** Waiting for the main menu to be up. */
        MENU,
        /** The world was asked for; waiting for it to arrive. */
        LOADING,
        /** In the world, letting the chunks around the spawn be built. */
        SETTLING,
        /** Flying the route. */
        FLYING,
        /** Finished; the client is on its way down. */
        DONE
    }

    private static Stage stage = ROUTE.isEmpty() ? Stage.OFF : Stage.MENU;

    /** Ticks spent in the current stage. */
    private static int ticks;

    /** Ticks spent since the mod started, for the patience limit. */
    private static int total;

    /** Where the route is flown from, taken from the world's own spawn. */
    private static double baseX;
    private static double baseY;
    private static double baseZ;

    /** Set by the tick, acted on by the frame: the name to save the next frame under. */
    private static String pendingShot;

    /** How many frames have been kept. */
    private static int shotsTaken;

    /** Where the camera was last tick, so that the frames between can be drawn between. */
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static float lastYaw;
    private static float lastPitch;
    private static boolean havePrevious;

    private Flight() {
    }

    /** Whether a flight was asked for at all. */
    public static boolean asked() {
        return !ROUTE.isEmpty();
    }

    /**
     * The state machine, one step per client tick.
     *
     * Every step is guarded and every failure ends the run rather than
     * retrying: this is a thing that runs while nobody is looking, and a loop
     * that keeps trying is a loop that reports nothing at all.
     */
    public static void tick() {
        if (stage == Stage.OFF || stage == Stage.DONE) {
            return;
        }
        try {
            step();
        } catch (Throwable t) {
            VulkanModNext.LOGGER.error("Flight failed and the run is being ended", t);
            finish("failed");
        }
    }

    private static void step() {
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        total++;
        if (stage != Stage.FLYING && total > PATIENCE * TICKS_PER_SECOND) {
            VulkanModNext.LOGGER.error("Flight gave up after {} seconds at stage {}",
                    PATIENCE, stage);
            finish("gave up at " + stage);
            return;
        }
        switch (stage) {
            case MENU:
                // The menu is the one moment where the game is certainly idle
                // and certainly has no world. Asking earlier means asking while
                // the resources are still being loaded — and on this version it
                // also means asking before Forge has finished filling its frozen
                // registries, which kills the world on its own saved snapshot.
                if (mc.screen instanceof MainMenuScreen) {
                    launchWorld(mc);
                }
                break;
            case LOADING:
                if (mc.level != null && mc.player != null && mc.screen == null) {
                    enterWorld(mc);
                }
                break;
            case SETTLING:
                // Held in place for the whole wait rather than left alone,
                // because the player falls otherwise and the route would start
                // from somewhere it did not choose.
                place(mc, baseX, baseY, baseZ, yawAt(0), pitchAt(0), true);
                holdSky(mc);
                if (ticks >= SETTLE * TICKS_PER_SECOND) {
                    stage = Stage.FLYING;
                    ticks = 0;
                    VulkanModNext.LOGGER.info("Flight {} route {} begins, seed {}, distance {}",
                            TAG, ROUTE, SEED, DISTANCE);
                }
                break;
            case FLYING:
                sampleFrameRate(mc);
                holdSky(mc);
                fly(mc);
                break;
            default:
                break;
        }
    }

    private static void launchWorld(Minecraft mc) {
        String folder = "flight-" + SEED;
        // Whether this route has been flown here before, which decides whether
        // the numbers from it can be compared with anybody else's.
        //
        // The world is kept rather than made fresh each time, on purpose: a
        // world made from the same seed is the same world, and keeping it means
        // the terrain along the route is already on disk. What that costs is
        // that the very first run of a route is flown while the server is still
        // generating it, and generation is not the renderer — it lands in the
        // frame anyway, and it made two runs of the identical build differ by
        // forty per cent on 1.12.2 before this line existed. So: the first run
        // of a seed at a given distance is a warm-up and its numbers are thrown
        // away.
        boolean firstTime = !mc.getLevelSource().levelExists(folder);
        VulkanModNext.LOGGER.info("Flight {} opening world {} from seed {} ({})", TAG, folder, SEED,
                firstTime ? "COLD - generating, discard these numbers" : "warm, already on disk");
        if (!firstTime) {
            mc.loadLevel(folder);
            stage = Stage.LOADING;
            ticks = 0;
            return;
        }
        // Spectator: no collision and no physics, so a route is exactly the line
        // it was written as rather than the line the terrain allowed.
        DynamicRegistries.Impl registries = DynamicRegistries.builtin();
        DimensionGeneratorSettings generator = new DimensionGeneratorSettings(
                SEED, true, false,
                DimensionGeneratorSettings.withOverworld(
                        registries.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY),
                        DimensionType.defaultDimensions(
                                registries.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY),
                                registries.registryOrThrow(Registry.BIOME_REGISTRY),
                                registries.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY),
                                SEED),
                        DimensionGeneratorSettings.makeDefaultOverworld(
                                registries.registryOrThrow(Registry.BIOME_REGISTRY),
                                registries.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY),
                                SEED)));
        WorldSettings settings = new WorldSettings(folder, GameType.SPECTATOR, false,
                Difficulty.PEACEFUL, true, new GameRules(), DatapackCodec.DEFAULT);
        mc.createLevel(folder, settings, registries, generator);
        stage = Stage.LOADING;
        ticks = 0;
    }

    private static void enterWorld(Minecraft mc) {
        if (!PRESET.isEmpty()) {
            // Said out loud rather than ignored: a run flown with the wrong
            // settings and a run flown with the right ones look identical in
            // the log, and only one of them means anything.
            VulkanModNext.LOGGER.warn("Flight {} was asked for preset '{}', and this port has no "
                    + "presets — flying with whatever the config file holds", TAG, PRESET);
        }
        // Focus is not a thing an unattended run can promise, and a game that
        // pauses when the window loses it would flat-line halfway through.
        mc.options.pauseOnLostFocus = false;
        // The debug screen is where the game keeps its own profiler, and it
        // fills in only while it is open. It is also printed over the top third
        // of every frame, so it is asked for rather than assumed.
        mc.options.renderDebug = PROFILER;
        // Unpinned from the monitor, and this is the difference between a
        // measurement and a reading of somebody's display.
        //
        // Found on the very first run of this route: median 119, worst 118,
        // best 119, over ten seconds — a number that flat cannot be a renderer,
        // it is a 120 Hz panel. Everything the route exists to compare was
        // hidden behind it, and a frozen number reads exactly like a result.
        //
        // Both are needed: the option is what the game consults when it decides
        // whether to wait, and the window is what actually holds the swap
        // interval, so setting one without the other changes nothing until
        // something else happens to reapply it.
        mc.options.enableVsync = false;
        mc.getWindow().updateVsync(false);
        mc.options.framerateLimit = FRAMERATE;
        mc.getWindow().setFramerateLimit(FRAMERATE);
        applyWindow(mc);
        if (CLOUDS >= 0) {
            mc.options.renderClouds = CLOUDS == 0 ? CloudOption.OFF
                    : CLOUDS == 1 ? CloudOption.FAST : CloudOption.FANCY;
        }
        if (mc.options.renderDistance != DISTANCE) {
            mc.options.renderDistance = DISTANCE;
            mc.levelRenderer.allChanged();
        }
        // Twelve blocks over the spawn: high enough to be out of a tree and low
        // enough to still have the ground fill the view, and derived from the
        // world rather than written down, so the same route works on any seed it
        // is pointed at.
        //
        // The world's spawn point, not the player's position: a player is put
        // down at a random spot inside the spawn area, so two runs of the same
        // seed start tens of blocks apart and the frames cannot be compared.
        BlockPos spawn = mc.level.getSharedSpawnPos();
        baseX = spawn.getX() + 0.5;
        baseY = spawn.getY() + 12.0;
        baseZ = spawn.getZ() + 0.5;
        String[] given = POSITION.isEmpty() ? null : POSITION.split(",");
        if (given != null && given.length == 3) {
            baseX = Double.parseDouble(given[0].trim());
            baseY = Double.parseDouble(given[1].trim());
            baseZ = Double.parseDouble(given[2].trim());
        }
        VulkanModNext.LOGGER.info("Flight {} in the world at {} {} {}, settling {}s",
                TAG, (int) baseX, (int) baseY, (int) baseZ, SETTLE);
        stage = Stage.SETTLING;
        ticks = 0;
    }

    /**
     * Holds the hour and the weather, every tick, on the server that owns them.
     *
     * <h3>Why every tick and not once</h3>
     *
     * The daylight cycle would otherwise walk the sun away from where the route
     * put it during the thirty seconds it is flown, and a shadow that moves
     * between the first frame and the last is a difference in the picture that
     * belongs to the clock rather than to the renderer. Re-asserting is cheaper
     * to write than turning the game rule off and covers the same ground.
     *
     * <h3>Why on the server and not through this mod</h3>
     *
     * The 1.12.2 harness holds these with the renderer's own time control,
     * which this port does not have. Held here, they are held for whoever is
     * drawing — so a vanilla control run and a Vulkan run see the same sky,
     * which is the only way the two are comparable at all.
     */
    private static void holdSky(Minecraft mc) {
        if (HOUR < 0 && WEATHER <= 0) {
            return;
        }
        net.minecraft.server.integrated.IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        // Handed to the server's own thread: the world is its state, and the
        // client tick is not the thread that owns it.
        server.execute(() -> {
            ServerWorld level = server.getLevel(World.OVERWORLD);
            if (level == null) {
                return;
            }
            if (HOUR >= 0) {
                // Minecraft's day starts at dawn, six hours behind the clock.
                level.setDayTime((HOUR - 6L + 24L) % 24L * 1000L);
            }
            if (WEATHER == 1) {
                level.setWeatherParameters(Integer.MAX_VALUE, 0, false, false);
            } else if (WEATHER == 2) {
                level.setWeatherParameters(0, Integer.MAX_VALUE, true, false);
            } else if (WEATHER == 3) {
                level.setWeatherParameters(0, Integer.MAX_VALUE, true, true);
            }
        });
    }

    /**
     * Resizes the window, if one was asked for, before anything is measured.
     *
     * Through GLFW, which is what this version's window is: the 1.12.2 harness
     * calls {@code Display.setDisplayMode}, and that class does not exist here.
     * The game learns about the change through the same resize callback a
     * person dragging the window edge would trigger, so every framebuffer sized
     * to the window follows.
     */
    private static void applyWindow(Minecraft mc) {
        if (WINDOW.isEmpty()) {
            return;
        }
        int cross = WINDOW.indexOf('x');
        if (cross <= 0) {
            VulkanModNext.LOGGER.warn("Flight {} could not read a window size from '{}'", TAG, WINDOW);
            return;
        }
        try {
            int width = Integer.parseInt(WINDOW.substring(0, cross).trim());
            int height = Integer.parseInt(WINDOW.substring(cross + 1).trim());
            // Only ever a window. The same call against a full-screen display
            // sets the mode of the monitor itself, which blanks the screen while
            // it re-syncs — a measurement flag has no business doing that to
            // somebody's monitor.
            if (mc.options.fullscreen) {
                VulkanModNext.LOGGER.warn("Flight {} will not resize a full-screen display; "
                        + "run windowed to pin the window", TAG);
                return;
            }
            GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), width, height);
            VulkanModNext.LOGGER.info("Flight {} window pinned to {}x{}", TAG, width, height);
        } catch (NumberFormatException e) {
            VulkanModNext.LOGGER.warn("Flight {} could not set the window to '{}'", TAG, WINDOW, e);
        }
    }

    private static void fly(Minecraft mc) {
        int length = SECONDS * TICKS_PER_SECOND;
        // Measured from the first tick of the route rather than from zero, so
        // that the first frame is at nothing and the last is at exactly one
        // whole turn. Off by a single tick, the two ends of a spin are two
        // slightly different views and the one comparison this route exists for
        // compares a fault with a rounding error.
        float t = length <= 1 ? 1.0f
                : Math.min(1.0f, (float) (ticks - 1) / (float) (length - 1));
        double x = baseX;
        double z = baseZ;
        if ("line".equals(ROUTE)) {
            // Eight blocks a second, a little over sprint speed, which is what
            // makes chunks arrive at the rate a player would meet.
            x += ticks * 0.4;
        }
        // Decided before the camera is placed, because a tick that is about to
        // be photographed is placed differently: pinned rather than
        // interpolated, so the picture is of the angle the route names.
        boolean shooting = shotsTaken < SHOTS && ticks >= shotTick(shotsTaken, length);
        if (shooting) {
            pendingShot = "flight-" + TAG + "-" + ROUTE + "-"
                    + String.format("%02d", shotsTaken);
            shotsTaken++;
        }
        place(mc, x, baseY, z, yawAt(t), pitchAt(t), shooting);
        // Five ticks of grace after the last frame is asked for. The frame that
        // saves it has not run yet at this point — a picture is taken at the end
        // of a frame and this is the tick before one — so closing the game here
        // loses exactly the frame the route was flown to reach.
        if (ticks >= length + 5) {
            finish("finished");
        }
    }

    /**
     * Puts the camera exactly where it is asked, this tick and last tick.
     *
     * The previous values matter as much as the current ones: everything drawn
     * is interpolated between the two, so setting only the current pair leaves
     * the camera sliding towards it across the frame and a screenshot taken from
     * a named angle is not of that angle.
     */
    private static void place(Minecraft mc, double x, double y, double z, float yaw, float pitch,
                              boolean pinned) {
        if (mc.player == null) {
            return;
        }
        // Wrapped before it is handed over, and this is not tidiness.
        //
        // The game normalises a player's yaw itself, once a tick. Hand it 405
        // and next tick the current angle is 45 while the previous one is still
        // 405 — and every frame between the two ticks is drawn at a mixture of
        // the two, which is a camera whipping backwards through the entire
        // circle. On 1.12.2 the route reported 45 and the picture was of 71.
        yaw = MathHelper.wrapDegrees(yaw);
        // Pinning both ends of that pair is what a photograph needs and what
        // ordinary motion must not have: the route moves the camera twenty times
        // a second, and with nothing to interpolate towards, five hundred frames
        // a second look exactly like twenty. Worse than looking wrong, it
        // measures wrong — everything that skips work when the camera has not
        // moved gets an easier ride than it would in a game somebody is playing.
        //
        // The previous angle is carried as a difference rather than as the last
        // value, so that a route crossing the half-turn does not hand the
        // interpolation a pair three hundred and sixty degrees apart.
        double fromX = pinned || !havePrevious ? x : lastX;
        double fromY = pinned || !havePrevious ? y : lastY;
        double fromZ = pinned || !havePrevious ? z : lastZ;
        float fromYaw = pinned || !havePrevious ? yaw
                : yaw - MathHelper.wrapDegrees(yaw - lastYaw);
        float fromPitch = pinned || !havePrevious ? pitch : lastPitch;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        havePrevious = true;
        mc.player.absMoveTo(x, y, z, yaw, pitch);
        mc.player.xo = fromX;
        mc.player.yo = fromY;
        mc.player.zo = fromZ;
        mc.player.xOld = fromX;
        mc.player.yOld = fromY;
        mc.player.zOld = fromZ;
        mc.player.yRotO = fromYaw;
        mc.player.xRotO = fromPitch;
        mc.player.yHeadRot = yaw;
        mc.player.yHeadRotO = fromYaw;
        mc.player.setDeltaMovement(0.0, 0.0, 0.0);
    }

    /**
     * Which tick frame {@code index} is taken on, ends included.
     *
     * Spacing by a step and testing the remainder misses both ends — the first
     * tick is one rather than zero, and the last lands wherever the division
     * left it. Both ends are the interesting ones here.
     */
    private static int shotTick(int index, int length) {
        if (SHOTS <= 1 || length <= 1) {
            return 1;
        }
        return 1 + (int) ((long) index * (length - 1) / (SHOTS - 1));
    }

    private static float yawAt(float t) {
        if ("spin".equals(ROUTE)) {
            // A whole turn, so the last frame is the first one again.
            return YAW + 360.0f * t;
        }
        if ("line".equals(ROUTE)) {
            // Along the line of travel, which is what makes the chunks that are
            // arriving the ones being looked at.
            return -90.0f;
        }
        return YAW;
    }

    private static float pitchAt(float t) {
        // Slightly down at every angle by default: level puts half the screen in
        // sky, and sky is the one thing here that costs nothing to draw.
        return PITCH;
    }

    /**
     * The frame hook, which is where a screenshot has to be taken.
     *
     * A tick knows which frame it wants and cannot save one — at tick time the
     * picture belongs to the frame before it, and half of it may not have been
     * drawn. The tick leaves a name here and the frame acts on it.
     */
    public static void onFrameEnd() {
        if (stage != Stage.FLYING || pendingShot == null) {
            return;
        }
        String name = pendingShot;
        // The same frame again on the very next one, when asked. A camera
        // turning at eighteen degrees a second moves a twentieth of a degree
        // between two frames, so a pair that differs by more than a rounding
        // error says the picture being read is not the picture just drawn.
        if (PAIRS && !name.endsWith("b")) {
            pendingShot = name + "b";
        } else {
            pendingShot = null;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            ScreenShotHelper.grab(mc.gameDirectory, name + ".png",
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                    mc.getMainRenderTarget(), message -> { });
            // The camera goes in the line with the frame. Two frames the route
            // says are of the same view have to be checkable against something
            // other than the route saying so.
            String where = mc.player == null ? "no player"
                    : String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f yaw %.3f pitch %.3f",
                            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                            mc.player.yRot, mc.player.xRot);
            VulkanModNext.LOGGER.info("Flight {} frame {} at {}", TAG, name, where);
        } catch (Throwable t) {
            VulkanModNext.LOGGER.warn("Flight could not keep frame {}", name, t);
        }
    }

    /**
     * The game's own frame counter, once a second, for the length of the route.
     *
     * The mod's own report has better numbers and cannot be used to compare
     * renderers: it belongs to this mod, and when another renderer is in the
     * folder this mod stands aside and takes the report with it. The counter in
     * the corner belongs to the game and is there whoever is drawing, which is
     * the only thing that makes a row of a comparison table mean the same as the
     * row above it.
     *
     * <p>Read out of {@code fpsString} because the number itself is private on
     * this version. The string is built as {@code "<n> fps T: …"}, so the
     * leading integer is the counter; anything else is refused rather than
     * guessed at.
     */
    private static void sampleFrameRate(Minecraft mc) {
        // Not the first reading. The counter is updated once a second by the
        // game, so at the moment the route begins it still holds whatever was
        // true a second ago — which is the loading screen, drawing nothing, at a
        // frame rate that belongs to no measurement. On 1.12.2 it arrived as a
        // "best" of 1671 in a run whose every other second was near 650.
        if (ticks < TICKS_PER_SECOND || ticks % TICKS_PER_SECOND != 0) {
            return;
        }
        String text = mc.fpsString;
        if (text == null) {
            return;
        }
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return;
        }
        int fps = Integer.parseInt(text.substring(0, end));
        if (fps > 0) {
            frameRates.add(fps);
        }
    }

    private static void reportFrameRate() {
        if (frameRates.isEmpty()) {
            return;
        }
        java.util.List<Integer> sorted = new java.util.ArrayList<>(frameRates);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        StringBuilder series = new StringBuilder();
        for (Integer value : frameRates) {
            series.append(series.length() == 0 ? "" : " ").append(value);
        }
        VulkanModNext.LOGGER.info(
                "Flight {} frame rate: median {}, 5% low {}, worst {}, best {}, over {} seconds"
                        + " — {}",
                TAG, sorted.get(n / 2), sorted.get(n / 20), sorted.get(0), sorted.get(n - 1), n,
                series);
    }

    private static void finish(String why) {
        if (stage == Stage.DONE) {
            return;
        }
        stage = Stage.DONE;
        reportFrameRate();
        // What the renderer itself counted, beside what the game counted. The
        // two answer different questions and a run that prints only one of them
        // cannot say whether a frame rate moved because the renderer changed or
        // because it drew a different number of chunks.
        VulkanModNext.LOGGER.info("Flight {} {}: {}", TAG, why, TerrainFrame.layerReport());
        net.vulkanmodnext.vkimpl.VkContext context = VulkanStartup.context();
        if (context != null) {
            VulkanModNext.LOGGER.info("Flight {} terrain:\n{}", TAG, context.terrainDiagnostics());
        }
        VulkanModNext.LOGGER.info("Flight {} {} after {} frames kept", TAG, why, shotsTaken);
        try {
            Minecraft.getInstance().stop();
        } catch (Throwable t) {
            VulkanModNext.LOGGER.warn("Flight could not close the game", t);
        }
    }
}
