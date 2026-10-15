package net.vulkanmodnext.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every setting the mod has, declared once.
 *
 * <h2>Generated, and why</h2>
 *
 * This file is written by {@code tools/lift-settings.py} out of the 1.12.2
 * mod's own declarations — the keys, the categories, the defaults, the ranges
 * and the sentences describing them. Retyping a hundred and seven settings
 * would be a hundred and seven chances to get a default wrong, and a wrong
 * default is the invisible kind of wrong: the setting works, and behaves
 * differently from the one the player already knows.
 *
 * <h2>Which ones are marked as working, and on what evidence</h2>
 *
 * Not on the code having been written: on a picture. Each live flag below was
 * set after the effect was turned to both of its ends on the same route, in the
 * same world, at the same hour, and the two frames were compared. The bar is
 * the noise floor of that comparison, measured from three identical runs and
 * sitting at 0.07% of pixels differing by more than eight of 255 — clouds move
 * and chunks arrive, so two runs are never byte-identical. Anything at that
 * floor is not marked live, however finished its code looks.
 *
 * <h2>Declared is not the same as working</h2>
 *
 * Most of these do nothing here yet, because the part of the mod they steer has
 * not been ported. They are listed anyway, and each one says which it is. That
 * is the whole point of showing them: a menu that hides what is missing tells a
 * player the port is further along than it is, and a menu whose switches
 * silently do nothing is worse than one that has none.
 */
public final class Settings {

    public enum Category { GENERAL, QUALITY, OPTIMIZATION, ADVANCED }

    /** One setting: what it is called, what it may hold, and whether it bites. */
    public static final class Setting {

        public final String key;
        public final Category category;
        public final boolean bool;
        public final int min;
        public final int max;
        public final int fallback;
        public final String description;
        /** False when this is declared and remembered but steers nothing yet. */
        public final boolean live;

        Setting(String key, Category category, boolean bool, int min, int max, int fallback,
                boolean live, String description) {
            this.key = key;
            this.category = category;
            this.bool = bool;
            this.min = min;
            this.max = max;
            this.fallback = fallback;
            this.live = live;
            this.description = description;
        }

        /** A readable name, made from the key rather than kept beside it. */
        public String title() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (i == 0) {
                    out.append(Character.toUpperCase(c));
                } else if (Character.isUpperCase(c)) {
                    out.append(' ').append(c);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    private static final List<Setting> ALL = new ArrayList<>();

    private static void add(String key, Category category, boolean bool, int min, int max,
                            int fallback, boolean live, String description) {
        ALL.add(new Setting(key, category, bool, min, max, fallback, live, description));
    }

    public static List<Setting> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static List<Setting> of(Category category) {
        List<Setting> out = new ArrayList<>();
        for (Setting setting : ALL) {
            if (setting.category == category) {
                out.add(setting);
            }
        }
        return out;
    }

    /** How many of them actually steer something in this port. */
    public static int liveCount() {
        int n = 0;
        for (Setting setting : ALL) {
            if (setting.live) {
                n++;
            }
        }
        return n;
    }

    private Settings() {
    }

    static {
        add("ambientOcclusion", Category.GENERAL, false, 0, 100, 0, true,
                "How much a point is darkened by how little of its surroundings it can see, in "
                + "percent. 0 is off. Corners, the undersides of overhangs and the join between a "
                + "wall and a floor pick up shadow the game has no way to express. Terrain only, "
                + "and worked out from the depth buffer this renderer already has.");
        add("aoRadius", Category.GENERAL, false, 1, 6, 2, true,
                "How far a corner's shadow reaches, in blocks. Larger is softer and spreads "
                + "further from the seam; smaller keeps the shading tight against it.");
        add("bloom", Category.GENERAL, false, 0, 100, 0, true,
                "How much light spills off a glowing surface into what is around it, in "
                + "percent. 0 is off. Lava, torches, glowstone and any modded block that gives "
                + "off light. Terrain only: this mod's frame is finished before the game draws "
                + "entities and particles, so a burning creature does not glow.");
        add("celestialGlint", Category.GENERAL, false, 0, 100, 0, true,
                "The sun itself sliding along the ripples, and the moon doing the same at "
                + "night. A reflection cannot produce this: the sun is a light rather than a "
                + "surface that was drawn for a ray to find, so reflecting the sky where it is "
                + "gives its colour and not its shape. Fades with distance, which is deliberate - "
                + "a specular highlight physically widens towards the horizon as the eye rises, "
                + "and a sun that grows when you fly up reads as an error however correct it is.");
        add("cloudShadows", Category.GENERAL, false, 0, 100, 0, true,
                "How dark a shadow the clouds overhead cast on the world. Read from the very "
                + "sheet the game draws its clouds from, at the height the world reports and with "
                + "the drift the game itself counts - so the dark patch lands under the cloud "
                + "that cast it rather than beside it. Needs the clouds turned on and the sun "
                + "above the horizon; fades out near the horizon, where the journey up to the "
                + "cloud layer is long enough that the shadow lands nowhere near what is "
                + "overhead.");
        add("cloudTint", Category.GENERAL, false, 0, 100, 0, true,
                "How much of the sky's own colour the clouds take. Vanilla clouds are white at "
                + "noon and white at sunset, hanging in an orange sky. This mixes the colour the "
                + "game has already worked out for the horizon into them, which is the cheap half "
                + "of what a shader pack does to a sky - the expensive half is drawing the clouds "
                + "again as volumes.");
        add("colourVision", Category.GENERAL, false, 0, 3, 0, true,
                "Move the colours one kind of eye cannot separate into the channels it still "
                + "can. 0 off, 1 protanopia (red), 2 deuteranopia (green), 3 tritanopia (blue). "
                + "This is not a filter over the picture and not a simulation of what somebody "
                + "sees: the missing cone's response is rebuilt from the other two, and the "
                + "difference — which is the information being lost — is pushed into the channels "
                + "that survive. Redstone against stone and a lit torch against an unlit one are "
                + "what it is for. No preset touches this one: it describes the person, not the "
                + "look.");
        add("contactShadows", Category.GENERAL, false, 0, 100, 0, true,
                "How dark a short shadow cast along the ground towards the sun may go. It is "
                + "worked out from the depth of the picture rather than from geometry, so "
                + "whatever drew into that depth casts one - a chest, a creature, another mod's "
                + "machine - and nothing is taken away from any mod to get it. It can only find "
                + "something that is itself on the screen and within about a block of the "
                + "surface, which is why it is a contact shadow and not a shadow: it fills the "
                + "gap where a thing meets the floor, and the sun's own long shadows are the "
                + "traced ones. Shares the ambient occlusion pass, so it costs a loop rather than "
                + "a pass, and turn on Occlusion Over Everything for it to see anything but "
                + "blocks.");
        add("creatureLight", Category.GENERAL, false, 0, 100, 0, true,
                "How much a creature shades its own faces against the sun, so that a cow in a "
                + "lit world is lit like the world instead of flat against it. The face is taken "
                + "from the geometry being drawn rather than from the depth of the picture, so it "
                + "is exact and has no outline around it. Only the sky half of the game's own "
                + "lighting is moved, never the block half - a creature in a cave beside a torch "
                + "is left exactly as the game drew it, whatever this is set to, and that is by "
                + "construction rather than by tuning. Needs Draw Creatures in Vulkan.");
        add("directionalLightStrength", Category.GENERAL, false, 0, 100, 50, true,
                "How far dynamic light goes towards caring which way a surface is turned, in "
                + "percent. 0 is off. The game's own light is one number per block and knows "
                + "nothing about orientation, so a dropped torch lights the underside of the "
                + "floor it lies on as brightly as the top. This works the face out from the "
                + "shape of the surface on screen and dims what is turned away. Costs nothing "
                + "while dynamic lights are off.");
        add("dynamicLightDistance", Category.GENERAL, false, 1, 200, 160, false,
                "How far away a light source may be and still be drawn, in blocks. This is not "
                + "how far the light reaches — that comes from the source's own level, and a "
                + "torch lights about fifteen blocks around itself whatever this is set to. It "
                + "decides whether a distant torch lights the ground it is standing on at all, "
                + "and that pool of light is visible from as far as you can see the ground. Lower "
                + "it if you would rather only nearby sources counted; there is no meaningful "
                + "cost either way, because every loaded entity is looked at regardless and only "
                + "the nearest 32 sources are ever drawn.");
        add("dynamicLights", Category.GENERAL, true, 0, 1, 0, true,
                "Let a carried torch, a dropped glowing block or a burning creature light the "
                + "terrain around it. The light is added while the world is being shaded, so no "
                + "chunk is rebuilt for it — which is what makes this affordable, because "
                + "rebuilding chunks is what the frame is already waiting on when you move. It "
                + "lights terrain only: entities and anything else OpenGL still draws are "
                + "unaffected.");
        add("exposure", Category.GENERAL, false, 0, 100, 50, true,
                "How much light is let in before the film curve closes the range back down. The "
                + "middle is no change. Only means anything with the frame above turned on.");
        add("extremeRenderDistance", Category.GENERAL, true, 0, 1, 0, true,
                "Let the render-distance slider go past 64, up to 128. The game builds a render "
                + "chunk for every cell of a (2d+1) x (2d+1) x 16 grid before it draws anything: "
                + "266 256 of them at 64 and 1 056 784 at 128, four times the objects and four "
                + "times the memory, whether or not there is a world out there to put in them. "
                + "Turning this off again pulls the distance back to 64 if it is above it.");
        add("fog", Category.GENERAL, true, 0, 1, 1, true,
                "Fade Vulkan terrain into the distance the way the rest of the scene already "
                + "does. Off leaves the world ending in a hard edge, which is a little faster.");
        add("fogDistance", Category.GENERAL, false, 1, 400, 100, true,
                "How far the game's own distance fog reaches, as a percentage of what the game "
                + "chose. Raising it is what a raised render distance was for: vanilla ties the "
                + "haze to the distance, so more chunks arrive wrapped in more of it and look no "
                + "further away than before. Fog that tells you something — blindness, being "
                + "under water or in lava — is never rescaled.");
        add("foliageSway", Category.GENERAL, false, 0, 100, 0, true,
                "How far the top of a plant leans in the wind, in percent. 0 is off. Grass, "
                + "flowers, saplings and crops only: leaves are a solid cube and plants taller "
                + "than one block would come apart at the seam. Needs Material Tags on.");
        add("frameGraph", Category.GENERAL, true, 0, 1, 0, true,
                "Show a frame-time graph in the bottom-left corner, with the worst and best "
                + "frame of the last couple of seconds and the 1% low. An average framerate "
                + "cannot tell a steady 120 from a 240 that stalls every tenth frame; the 1% low "
                + "and the shape of the graph can.");
        add("frameGraphIntervalMs", Category.GENERAL, false, 100, 5000, 1000, true,
                "How often the frame graph recomputes the numbers above it, in milliseconds. "
                + "The trace itself always moves every frame; this is only the text. Figures that "
                + "change every frame cannot be read at all.");
        add("godRays", Category.GENERAL, false, 0, 100, 0, true,
                "How bright the shafts of light from the sun may be. Gathered from the finished "
                + "picture: the walk from a pixel towards the sun adds up what the sky shows "
                + "through, so anything standing in the way leaves a dark lane and a gap in a "
                + "canopy leaves a bright one. No geometry and no rays are involved, so it cannot "
                + "break another mod - and whatever a mod drew is in the picture and casts its "
                + "own shafts for free. Needs the sun above the horizon and roughly in front of "
                + "you; fades out rather than switching off as it leaves the screen.");
        add("hdrFrame", Category.GENERAL, true, 0, 1, 0, true,
                "Ask the game for a frame with room above white in it. Minecraft draws the "
                + "world into eight bits a channel, so anything brighter than white is cut off "
                + "before any effect here ever sees it - which is why the glow has no light to "
                + "add, a highlight on water arrives already flattened into a white patch, and "
                + "the tone curve can only tilt colours rather than shape the light. With this on "
                + "the frame holds sixteen bits a channel and the tone pass closes the range back "
                + "down along a film curve at the end. Applies at once, costs video memory, and "
                + "is asked of the driver first - if it will not have it, the log says so and "
                + "nothing changes. Goes back to eight bits by itself if the Vulkan renderer "
                + "stops drawing, because the pass that closes the range back down lives there.");
        add("heightFog", Category.GENERAL, false, 0, 100, 0, true,
                "How much colour the ground below you gives up to fog, in percent. 0 is off. It "
                + "fades towards the game's own fog colour and only where the game already has "
                + "fog, so it cannot invent a haze the sky disagrees with. It reaches only what "
                + "this renderer draws: entities and particles are fogged by OpenGL, which knows "
                + "nothing about height.");
        add("heightFogDepth", Category.GENERAL, false, 4, 96, 24, true,
                "The drop below the camera, in blocks, over which height fog reaches nearly all "
                + "of its strength. The strength setting says how much colour the low ground "
                + "gives up in the end; this says how far down you have to look before it does.");
        add("iceShine", Category.GENERAL, false, 0, 100, 0, true,
                "How much of the sky ice gathers on its surface. The game draws ice as a flat "
                + "blue pane; every shader pack makes it the most recognisable surface in the "
                + "world by giving it the one thing water already has here - a fresnel term, so "
                + "it looks along itself the way a polished floor does. Cheaper than water: ice "
                + "does not ripple, so there are no waves to shade and no ray to march.");
        add("leafGlow", Category.GENERAL, false, 0, 100, 0, true,
                "How brightly a leaf lets the sun through from behind it. The game shades a "
                + "leaf by how much light reaches it, so a tree with the sun behind it is a dark "
                + "cut-out - the light that goes through the leaf and on towards you is not in "
                + "that answer at all. This asks one question, whether the sun is behind this "
                + "leaf from where you are standing, and brightens it in its own colour when it "
                + "is. Costs a dot product on leaves and plants and nothing anywhere else; no "
                + "rays, so it works with tracing off.");
        add("leafShadows", Category.GENERAL, false, 0, 100, 0, true,
                "How much light gets through leaves and plants in a traced shadow. A ray cannot "
                + "read a texture, so this is how often a leaf quad stops light rather than where "
                + "its holes are - averaged over frames it comes out as dapple. Needs ray tracing "
                + "and sun shadows; costs more the more of the screen is under a tree.");
        add("lightSoftness", Category.GENERAL, false, 0, 100, 30, true,
                "How soft the edge of a shadow cast by a torch or a fire is. Separate from the "
                + "sun's, because a small flame close by and a star a long way off are not the "
                + "same kind of source. 0 gives the perfectly crisp edge.");
        add("moonSize", Category.GENERAL, false, 0, 100, 40, true,
                "How large the moon is drawn. Same trick as the sun: the quad the game gives it "
                + "cannot be resized from here, but how much of its picture the disc fills can.");
        add("overlayEnabled", Category.GENERAL, true, 0, 1, 0, false,
                "Show the legacy Vulkan diagnostic overlay.");
        add("preloadQueue", Category.GENERAL, false, 4, 128, 16, true,
                "How many chunks Offscreen Chunk Preload keeps queued for building at once. "
                + "Higher fills the world in faster and takes more of the frame while it does. "
                + "Does nothing unless that setting is on.");
        add("preloadScan", Category.GENERAL, false, 512, 32768, 4096, true,
                "How much of the chunk grid Offscreen Chunk Preload looks through each frame "
                + "while hunting for something to build. The whole grid at a render distance of "
                + "64 is a quarter of a million cells, so scanning all of it in one frame would "
                + "trade a slow fill for a stutter; this is how much of that walk is paid for per "
                + "frame. Does nothing unless that setting is on.");
        add("roundMoon", Category.GENERAL, true, 0, 1, 0, true,
                "Draw the moon as a round disc with a soft glow. The game does not draw a moon "
                + "so much as one cell of an eight-picture sheet chosen by tonight's phase, so "
                + "all eight are drawn here — a lit disc with the shadow creeping across it, "
                + "which is one circle cut by a moving ellipse. A single disc in its place would "
                + "be full every night of the month.");
        add("roundSun", Category.GENERAL, true, 0, 1, 0, true,
                "Draw the sun as a round, warm disc instead of vanilla's square. The picture is "
                + "built here rather than shipped, which is what makes its size and its warmth "
                + "sliders instead of a file. Nothing else about the sky changes: the quad, its "
                + "place, its blend and the moon are all still the game's.");
        add("sceneGamma", Category.GENERAL, false, 0, 100, 50, true,
                "How the finished frame is bent before it reaches the screen. Fifty is the "
                + "frame untouched, to the bit — above it lifts the picture and below it deepens "
                + "it. The range is deliberately narrow: past its ends a picture stops being "
                + "graded and starts being broken, and a control that can break the picture is "
                + "one somebody will reach for to fix something else. Applies at once and costs "
                + "nothing measurable.");
        add("sceneOcclusion", Category.GENERAL, true, 0, 1, 0, true,
                "Darken the corners of the whole picture rather than of the blocks alone. The "
                + "occlusion is otherwise computed inside this mod's own pass, from a depth image "
                + "holding terrain and nothing else, so a chest, a mob or a modded block casts "
                + "nothing into the floor under it. This reads the game's finished depth instead, "
                + "where all of them are. Costs one more pass over the frame and takes nothing "
                + "away from anyone: the picture is already drawn by then.");
        add("sceneTone", Category.GENERAL, false, 0, 100, 0, true,
                "How strongly the finished picture is graded — contrast in the middle, warmth "
                + "in the balance. Applied after the whole world is drawn, so it reaches "
                + "creatures and particles as well as blocks, and never the interface.");
        add("sceneWarmth", Category.GENERAL, false, 0, 100, 50, true,
                "Which way the grading leans. The middle is neutral, above it warm, below it "
                + "cold. Red gains what blue gives up, so the frame does not get brighter.");
        add("screenReflections", Category.GENERAL, false, 0, 100, 0, true,
                "How much of a water reflection is the world actually standing there rather "
                + "than the fog colour, in percent. 0 is off. The ray is followed across the "
                + "picture already drawn, so it can only find what is on screen: nothing off the "
                + "edge of it, nothing hidden behind something nearer, and no creatures, which "
                + "this renderer does not draw. Where the ray finds nothing the fog colour "
                + "answers, as it did before.");
        add("shadowSoftness", Category.GENERAL, false, 0, 100, 35, true,
                "How soft the edge of a traced shadow is. One ray gives one answer per pixel, "
                + "so at zero the edge follows the pixel grid; higher values spread that same ray "
                + "over a disc and trade the staircase for a dithered band. Costs nothing either "
                + "way — the number of rays does not change.");
        add("skyGradient", Category.GENERAL, false, 0, 100, 0, true,
                "How much deeper the sky gets away from the horizon. Vanilla's sky is one "
                + "colour from the horizon to straight overhead; this deepens the top of it "
                + "towards a night sky, using the game's own fog colour so it cannot disagree "
                + "with the horizon under it. Painted only where the terrain drew nothing, and "
                + "each pixel is asked where it actually looks rather than how high it sits on "
                + "the screen, so the deepest part stays overhead however the camera is tilted.");
        add("sunHaze", Category.GENERAL, false, 0, 100, 0, true,
                "How much the fog warms towards the sun and cools away from it. The game fogs "
                + "everything to one colour whichever way you face; the sky it hangs under does "
                + "not. Needs the game to have fog of its own to tint, so it does nothing where "
                + "fog is switched off, and nothing at night.");
        add("sunShadows", Category.GENERAL, false, 0, 100, 0, true,
                "How dark the sun's shadow is, traced against the terrain. Needs Terrain "
                + "Acceleration Structures and a card that can trace from a shader. The shadow "
                + "lowers how much sky light a surface gets rather than darkening the finished "
                + "colour, which is how the game itself shades and why it does not touch a cave "
                + "lit by a torch.");
        add("sunSize", Category.GENERAL, false, 0, 100, 50, true,
                "How large the disc is drawn. The quad the game gives the sun cannot be resized "
                + "from here, but how much of it the disc fills can, which comes to the same "
                + "thing. The middle of the range is close to where vanilla put it.");
        add("sunWarmth", Category.GENERAL, false, 0, 100, 60, true,
                "How far towards orange the rim of the sun goes. 0 leaves it white. The centre "
                + "stays bright either way — a sun that is one flat colour looks painted on, and "
                + "the game's own is not flat either.");
        add("temporalAccumulation", Category.GENERAL, false, 0, 100, 60, false,
                "How much of what a pixel looked like last frame it keeps. A traced shadow is "
                + "worked out from one ray per pixel, which is grain; the rays are aimed "
                + "differently each frame and averaged here, which is a soft edge. Costs one "
                + "fullscreen pass and does nothing at all unless something is being traced.");
        add("terrainEnabled", Category.GENERAL, true, 0, 1, 1, true,
                "Render supported terrain layers through Vulkan. Disabling immediately returns "
                + "terrain to vanilla OpenGL.");
        add("timeControl", Category.GENERAL, false, 0, 2, 0, true,
                "Whether the time of day you see is the world's own, held where it was, or set "
                + "by the slider below. Nothing is sent to the server and nothing is stored: mobs "
                + "still burn at dawn whatever this says.");
        add("timeOfDay", Category.GENERAL, false, 0, 23, 12, true,
                "Which hour to show when the control above is set to Fixed. The day is kept, so "
                + "the moon keeps the phase it was going to have.");
        add("tracedBlockLight", Category.GENERAL, false, 0, 100, 0, false,
                "How much of the game's own block light to replace with light traced from the "
                + "blocks that emit it. Vanilla's is a flood fill: right around corners and "
                + "completely flat, with no idea where the light came from. Traced light has a "
                + "direction and a shadow. Only sources near you are known and only the nearest "
                + "thirty-two fit, so turned up fully a cave lit from further off goes dark.");
        add("tracedLights", Category.GENERAL, false, 0, 8, 2, false,
                "How many moving lights a surface may ask whether something is in the way. A "
                + "carried torch or a burning creature is added as a straight line from the "
                + "source, which is why it lights the far side of a wall; tracing the line is "
                + "what stops it. One ray per light per surface, so this is the cost. Needs "
                + "Terrain Acceleration Structures.");
        add("updateCheck", Category.GENERAL, true, 0, 1, 1, false,
                "Ask once when the game starts whether a newer build of this mod exists, and "
                + "say so at the top of this screen. Both the page it is published on and the "
                + "repository it is built from are asked, because a build can be on one and not "
                + "yet on the other, and the button then leads to whichever of them actually has "
                + "it. Two GET requests with no query, no body and no identifier: the only thing "
                + "said about you is a user agent naming this mod and its version, which one of "
                + "the two services refuses a request without. Nothing about the machine, the "
                + "player, the world or the other mods is collected or sent. Off means the "
                + "requests are never made.");
        add("waterCaustics", Category.GENERAL, false, 0, 100, 0, true,
                "How much light gathers into moving bands on the bed of shallow water. Real "
                + "caustics are the surface acting as a lens on the light going through it; this "
                + "brightens what is already being fetched from under the water in the same "
                + "pattern the waves are shaded by, which is the same picture for a fraction of "
                + "the price. Needs Water Refraction, because that is what fetches the bed in the "
                + "first place.");
        add("waterReflection", Category.GENERAL, false, 0, 100, 0, true,
                "How much of a water surface turns into a reflection of the sky as you look "
                + "along it, in percent. 0 is off. What it reflects is the game's own fog colour, "
                + "which at a grazing angle is what the horizon is, so it follows sunrise, "
                + "weather and being underwater by itself. Needs Vulkan Water and Glass on.");
        add("waterRefraction", Category.GENERAL, false, 0, 100, 0, true,
                "How much the surface of water bends what is seen through it. Reflection and "
                + "refraction are two halves of one thing and only one of them was here: a pond "
                + "whose mirror moves while its bed stays put reads as glass laid over a "
                + "photograph. What is behind the water is fetched from the same picture the "
                + "reflection searches, so it shows the world but not creatures, which this "
                + "renderer does not draw.");
        add("waterWaves", Category.GENERAL, false, 0, 100, 0, true,
                "How much a moving wave pattern tilts the water surface, in percent. 0 is off. "
                + "Nothing is displaced: the water stays flat and only the direction it is "
                + "treated as facing moves, so the sky reflection breaks up along the crests and "
                + "a torch scatters across it. Needs Vulkan Water and Glass on.");
        add("weatherControl", Category.GENERAL, false, 0, 3, 0, true,
                "Whether the weather you see is the world's own or one you pick. Local to this "
                + "screen: a storm the server believes in still charges a creeper.");
        add("wetSurfaces", Category.GENERAL, false, 0, 100, 0, true,
                "How much rain makes upward-facing surfaces gather the sky. Only faces pointing "
                + "up, and only in proportion to how much sky light they already receive - there "
                + "is no test for whether this particular block is under a roof, so a lit cave "
                + "mouth will damp a little too. A mood rather than a simulation, like the height "
                + "fog.");
        add("zoom", Category.GENERAL, true, 0, 1, 1, false,
                "Hold-to-zoom on the key bound in Controls.");
        add("zoomFactor", Category.GENERAL, false, 2, 10, 4, false,
                "How far the zoom key narrows the field of view. 4 means a quarter of it.");
        add("animatedTextures", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Update animated block textures. Off skips the per-tick frame uploads for every "
                + "animated sprite.");
        add("backgroundFpsLimit", Category.OPTIMIZATION, false, 0, 60, 10, false,
                "Framerate cap while the game window is not active. 0 disables the cap.");
        add("buildNearOffThread", Category.OPTIMIZATION, true, 0, 1, 0, true,
                "Queue a chunk that changed close to you for a builder thread instead of "
                + "rebuilding it on the thread that draws. Vanilla rebuilds anything dirty within "
                + "about 28 blocks of the eye right there in the middle of setting the frame up, "
                + "and the frame waits for it: measured, that is 0.4 to 0.7 ms a frame while it "
                + "is happening, which is nearly all of what that step costs. The price is that a "
                + "chunk you just changed catches up a frame or two later rather than instantly. "
                + "Forge has the same switch and it wins when it is on.");
        add("cacheBlockEntityModels", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Record the primed TNT cube once and replay it, instead of looking the model up "
                + "and rebuilding its twelve quads for every charge in every frame. The picture "
                + "is identical — the list is recorded from the game's own call.");
        add("chunkBuildThreads", Category.OPTIMIZATION, false, 0, 64, 0, true,
                "How many threads build chunk geometry. 0 keeps vanilla's count, which it "
                + "derives from the heap rather than the CPU and so caps well below a large core "
                + "count. Takes effect on the next world load.");
        add("chunkPreload", Category.OPTIMIZATION, true, 0, 1, 0, true,
                "Let chunks outside the view be rebuilt. Vanilla only ever schedules chunks "
                + "that are currently on screen, so at high render distances the world fills in "
                + "along whatever you are looking at.");
        add("dropVanillaBuffers", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Stop filling the game's own chunk buffers once Vulkan has the geometry. The "
                + "world is held twice in video memory today; this removes one of the copies and "
                + "halves what a render distance costs there. It also takes the second upload out "
                + "of the budget the game reserves each frame for getting chunks onto the card, "
                + "which is what actually limits how fast a world fills in. On by default since "
                + "the translucent layer started going through Vulkan everywhere: what it costs "
                + "is that a fallback to vanilla rendering has to rebuild the entire world first, "
                + "which is a pause, not a hole.");
        add("entityDistance", Category.OPTIMIZATION, false, 0, 256, 0, false,
                "Stop drawing entities past this many blocks. 0 keeps vanilla's per-entity "
                + "limit.");
        add("explosionParticles", Category.OPTIMIZATION, false, 0, 20000, 0, false,
                "How many particles one tick's explosions may spawn before they are thinned. "
                + "The client is sent the list of blocks an explosion destroyed and asks for two "
                + "particles at each one, so a large charge of TNT is tens of thousands of them "
                + "born in a single tick. 0 leaves it to the game.");
        add("fastFrustumTest", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Decide whether a box is off screen from its far corner rather than from all "
                + "eight of them. The same answer by the same arithmetic: the corner furthest "
                + "along a plane's normal is the last one to leave it, so if it is outside, all "
                + "of them are. Vanilla checks up to forty-eight corner positions to reject one "
                + "box, and the chunk visibility search does this once for every chunk it "
                + "reaches. On by default; the switch is for ruling it out, not for choosing.");
        add("fastRebuildNear", Category.OPTIMIZATION, true, 0, 1, 0, false,
                "Hand the last loop of the terrain setup only the chunks it can act on. That "
                + "loop walks every chunk on screen every frame — some 8 600 at render distance "
                + "64, and the game's own profiler puts it at 19% of the frame — to find the few "
                + "a block was broken in. The answer for each one is a single bit, and it now "
                + "lives in a flat array beside the grid rather than inside a chunk object "
                + "somewhere else in memory. Off by default because it replaces vanilla logic, "
                + "and the way that goes wrong is that something stops being rebuilt.");
        add("groupQuadFacings", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Sort each chunk's faces by which way they point, so the ones a camera cannot "
                + "possibly see are never read. Standing above a floor you cannot see its "
                + "underside, and the card knows that too — but it only finds out after reading "
                + "every one of those vertices, and reading vertices is what this renderer's "
                + "terrain pass is limited by. Measured at render distance 32: 36% of the reading "
                + "stops happening, and the frame rate rises eleven to thirteen per cent up to "
                + "1440p and five at 4K, where the frame is spending its time on pixels instead. "
                + "Takes effect on the next start, because one geometry buffer cannot hold two "
                + "orders at once. On by default; turn it off if a face ever goes missing where "
                + "the camera crosses a floor, a ceiling or a wall.");
        add("materialTags", Category.OPTIMIZATION, true, 0, 1, 0, true,
                "Record what each stretch of a chunk's geometry is made of while the chunk is "
                + "being built. On its own this changes nothing on screen: it is the groundwork "
                + "the remaining effects need. Water cannot be told from stained glass, nor grass "
                + "from torches, because vanilla's four render layers are not materials and the "
                + "vertex carries nothing else; the one place the answer exists is the rebuild "
                + "loop, for the length of one call. Costs a branch and a counter read per block "
                + "rendered, on the build threads. The diagnostics report says what that "
                + "measured.");
        add("nearPlaneHundredths", Category.OPTIMIZATION, false, 0, 50, 10, false,
                "Near clipping plane in hundredths of a block. 0 keeps vanilla's 0.05, which at "
                + "long render distances leaves the depth buffer unable to separate a snow layer "
                + "from the block under it. Larger values fix that and clip geometry very close "
                + "to the eye, which can open a hole when the head is inside a block. Costs "
                + "nothing either way: it is one number in the projection matrix.");
        add("ownVisibilityWalk", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Run this mod's own chunk visibility search instead of the game's. Same answer, "
                + "same every frame, but out of reused buffers rather than a fresh queue, set and "
                + "one object per visited chunk. The game's profiler puts its version at a "
                + "quarter to a half of the frame at render distance 64. On by default now, "
                + "having shipped off while it was new: it replaces vanilla logic, and the way "
                + "that goes wrong is that something quietly stops being drawn, so turn it off "
                + "first if anything is missing.");
        add("shortEntitySections", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Hand the entity and block-entity passes only the sections that can hold "
                + "something. Both of them walk every section on screen every frame — around 17 "
                + "700 at render distance 32, of which some 2 700 have any blocks in them at all "
                + "— and ask the world about each one before knowing whether anything stands "
                + "there. Measured with the scene held still at five drawn creatures, that walk "
                + "alone costs 0.07 ms at eight chunks and 1.10 at thirty-two. The creature list "
                + "is turned inside out instead: sixty entities each name the section they are "
                + "filed in, and the visibility search says in one array read whether it is on "
                + "screen. Needs Own Visibility Search on, and turn it off first if a creature or "
                + "a chest is not drawn where it should be.");
        add("shortLayerSections", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Give the game's own layer filter the sections that hold blocks instead of "
                + "every section on screen. That filter runs four times a frame, once per render "
                + "layer, and asks each section whether that layer is empty; at render distance "
                + "32 it asks around 17 700 and keeps under 2 700. Whether a section holds "
                + "anything at all is one bit on the same object the visibility search already "
                + "reads, so the list is kept while that search walks. It takes that step from "
                + "0.72 ms a frame to 0.45, and on the machine it was measured on that bought no "
                + "frames at all: once the entity passes were shortened the frame stopped waiting "
                + "on this thread. Measured again with the window made small enough that the "
                + "processor is what holds the frame back, which is the case this is for: seven "
                + "per cent, 1320 frames a second to 1424. Where the card is the ceiling it costs "
                + "nothing and shows nothing. On by default now that the list has been checked "
                + "against the full scan over 16 500 layer passes with nothing missing; turn it "
                + "off if a chunk ever stops being drawn.");
        add("smartAnimations", Category.OPTIMIZATION, true, 0, 1, 0, true,
                "Update only the animated block textures that are actually on screen. Vanilla "
                + "uploads a new frame for every animated sprite in the atlas every tick, whether "
                + "or not one block using it is in sight, and a modpack has hundreds. A chunk "
                + "records what it uses while it is built, so chunks in view have to be rebuilt — "
                + "F3+A — before this saves anything. Fluids, fire and portals are never skipped, "
                + "nor is any sprite that no chunk has ever used, which is what keeps an item in "
                + "a menu moving. Experimental: a texture that is both a block and an item can "
                + "stand still in your hand while no such block is in sight.");
        add("tileEntityDistance", Category.OPTIMIZATION, false, 0, 128, 0, false,
                "Stop drawing chests, signs and other block entities past this many blocks. 0 "
                + "keeps vanilla's.");
        add("visibilitySeedCache", Category.OPTIMIZATION, true, 0, 1, 1, false,
                "Reuse the seed of the chunk visibility search while the camera stays in the "
                + "same block and that block's chunk has not been rebuilt. Vanilla reads all 4096 "
                + "block states of the camera's own chunk section every time it redoes the "
                + "search, which at long render distances is hundreds of thousands of reads a "
                + "second for an answer that did not change.");
        add("vulkanParticles", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Draw particles with Vulkan. The game still decides where every particle is and "
                + "what it looks like — this changes only how the finished quads reach the card. "
                + "Vanilla hands them over as a client-side vertex array, which the driver has to "
                + "copy in full before it can start, and the game allows itself up to sixteen "
                + "thousand particles in each of six queues. Costs no extra work between the two "
                + "APIs: they join the pass that draws water.");
        add("vulkanTranslucent", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "Draw water and glass in Vulkan rather than leaving them on the OpenGL path. "
                + "Not a speed setting: the layer measures 2.6% of a frame either way. It is here "
                + "because the Vulkan terrain has fog and the OpenGL leftovers do not, so water "
                + "is currently the one surface that stays clear when everything around it fades. "
                + "On by default, once water, glass and the creatures seen through them had been "
                + "checked by eye.");
        add("vulkanWeather", Category.OPTIMIZATION, true, 0, 1, 1, true,
                "The same for rain and snow. A separate switch from the one above so that "
                + "either can be ruled out on its own.");
        add("atlasPixelsSeen", Category.ADVANCED, false, 0, 65536, 0, true,
                "Remembered, not set: how many pixels across the block atlas was last time. "
                + "Packed chunk vertices turn themselves off for a pack whose atlas is larger "
                + "than 8192, and this is how they know before the pack has loaded. Zero means no "
                + "atlas has been seen yet.");
        add("blockLightRadius", Category.ADVANCED, false, 4, 50, 24, false,
                "How far around you light-emitting blocks are looked for, in blocks. The search "
                + "runs a few times a second rather than every frame, is spread over several "
                + "frames, and skips whole sixteen-block sections that hold no block light at all "
                + "— so what widening it costs is far less than the volume suggests.");
        add("compactVertices", Category.ADVANCED, true, 0, 1, 1, true,
                "Pack each chunk vertex into 16 bytes instead of the 28 the game uses. The "
                + "position keeps 1/2048 of a block, which is 128 times finer than a texture "
                + "pixel; the light is exact; the colour is untouched. Measured at render "
                + "distance 32: the card's terrain time falls from 0.49 ms a frame to 0.42 and "
                + "the route from 521 frames a second to 547, and the geometry buffer from 428 "
                + "MiB of video memory to 240. The memory is the larger half of that and it is "
                + "what decides whether a long render distance fits at all. Takes effect on the "
                + "next start. Ray tracing works with it: the acceleration structures read the "
                + "packed positions through the matrix that unpacks them.");
        add("cullingEnabled", Category.ADVANCED, true, 0, 1, 1, true,
                "Skip triangles facing away from the camera. Off is for diagnosing geometry "
                + "only.");
        add("depthBlitEnabled", Category.ADVANCED, true, 0, 1, 1, true,
                "Copy Vulkan depth into the game's depth buffer with glBlitFramebuffer instead "
                + "of a shader.");
        add("entityCapture", Category.ADVANCED, true, 0, 1, 0, true,
                "Read what the game draws for every creature, and draw none of it. The first "
                + "step of moving entities to Vulkan: it measures how much of a scene comes "
                + "through the ordinary model path and what reading a pose per part costs. "
                + "Nothing on screen changes.");
        add("flatBlockColours", Category.ADVANCED, true, 0, 1, 0, true,
                "Draw every block face in one flat colour by reading the smallest level of the "
                + "block atlas. The cheapest a texture read can be; the world stops having "
                + "textures.");
        add("frameGraphCorner", Category.ADVANCED, false, 0, 3, 0, true,
                "Which corner the frame time graph sits in. The default is the bottom left, "
                + "which is where the chat window is.");
        add("framesInFlight", Category.ADVANCED, false, 1, 3, 2, true,
                "How many terrain frames the CPU may run ahead of the GPU. Higher smooths out "
                + "stalls at the cost of one frame of input latency and more memory.");
        add("geometryBudgetMiB", Category.ADVANCED, false, 0, 8192, 0, true,
                "VRAM in MiB the chunk geometry buffer may take before growth becomes cautious. "
                + "0 derives it from the amount of memory the GPU reports.");
        add("motionOverWorld", Category.ADVANCED, true, 0, 1, 0, true,
                "Show the motion over a dim ghost of the world instead of over black. Black "
                + "answers whether anything is moving; the ghost answers which part of it is.");
        add("rayTracing", Category.ADVANCED, true, 0, 1, 0, false,
                "Build acceleration structures over the terrain, which is what a ray needs "
                + "something to hit. Four effects wait on this and do nothing without it: the "
                + "sun's shadow, the shadow a moving light casts, traced block light, and the "
                + "light that comes through a canopy. Needs Vulkan 1.2 and the "
                + "acceleration-structure extension; the diagnostics report says what happened, "
                + "and the structures cost video memory and build time whether or not anything is "
                + "tracing against them. Takes effect on the next start.");
        add("rayTracingRadius", Category.ADVANCED, false, 32, 256, 96, false,
                "How far from you the terrain carries the structures a ray can hit, in blocks. "
                + "Decides both what can cast a shadow and what the whole feature costs in video "
                + "memory and build time; past it shadows fade out rather than stopping at a "
                + "circle. Takes effect immediately.");
        add("settingsRevision", Category.ADVANCED, false, 0, 1000, 0, false,
                "Which changed defaults have already been applied to this file. Not a setting.");
        add("showAccumulation", Category.ADVANCED, true, 0, 1, 0, true,
                "Diagnostic: paint each pixel by how much of its history it kept instead of by "
                + "the world. White is fully averaged, black is starting over — which is what the "
                + "edges of the screen and everything moving quickly should be.");
        add("showCreatureLight", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint creatures with the shading term on its own, flat grey, and nothing else. "
                + "White is a face turned to the sun and dark grey one turned away. The world "
                + "around them is left alone, which is the point: it shows whether the faces are "
                + "being found at all, separately from whether the setting is strong enough to "
                + "notice.");
        add("showMaterials", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint the terrain by what it is made of instead of by its texture: water blue, "
                + "foliage green, glass yellow, lava orange, everything else grey. A diagnostic "
                + "for the material buffer, which is invisible whether it is right or wrong. "
                + "Needs Material Tags on and a chunk rebuild to fill in.");
        add("showMotion", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint the world with how each pixel moved since the last frame instead of with "
                + "itself: which way it went is the colour, how fast is the brightness, and "
                + "anything that did not move is black. Frame averaging is what reads it — the "
                + "pass that turns one traced ray per pixel from grain into a soft edge — so it "
                + "is worked out only while something is being traced or while this view is open. "
                + "It is what reflections and any effect that remembers previous frames are built "
                + "on, and this is how to see whether it is right.");
        add("showOcclusion", Category.ADVANCED, true, 0, 1, 0, true,
                "Draw the ambient occlusion on its own, as flat grey, instead of applying it to "
                + "the world. Vanilla darkens the corners of its own blocks and darkens a face by "
                + "which way it points, so a dark seam in a lit room says nothing until those two "
                + "are out of the picture. This takes them out. Needs Ambient Occlusion above "
                + "zero.");
        add("showReflections", Category.ADVANCED, true, 0, 1, 0, true,
                "Paint the water with what the reflected ray found and nothing else: no fresnel "
                + "deciding how much of it to show, no water colour under it, deep blue wherever "
                + "the ray found nothing. Needs Screen Reflections above zero.");
        add("ultraLog", Category.ADVANCED, true, 0, 1, 0, true,
                "Write a detailed diagnostics report to logs/vulkanmodnext-diagnostics.log.");
        add("ultraLogSeconds", Category.ADVANCED, false, 1, 120, 10, true,
                "Seconds between diagnostics snapshots.");
        add("vulkanDevice", Category.ADVANCED, false, -1, 7, -1, true,
                "Which GPU Vulkan renders on, by the number the log gives it. -1 chooses "
                + "automatically, and automatic means the card OpenGL is already running on "
                + "rather than the fastest one present: memory cannot be shared between two "
                + "devices at all. Takes effect on the next start.");
        add("vulkanEntities", Category.ADVANCED, true, 0, 1, 1, true,
                "Draw creatures through Vulkan instead of letting the game draw them. "
                + "Experimental, and the first thing here that replaces vanilla's own drawing "
                + "rather than adding to it: a mod that builds its models some other way is "
                + "untouched and draws as it always did, but anything using the ordinary model "
                + "classes is taken. What it buys is not frames — it is that creatures exist in "
                + "this renderer at all, which is what reflections and glow have been waiting "
                + "for.");
    }
}
