package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.DimensionType;
import net.vulkanmodnext.client.WorldDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Changes what time of day the sky is drawn for, on this client.
 *
 * <h2>Why here, and not on the world</h2>
 *
 * On 1.12.2 every view of the hour ended at {@code World.getCelestialAngle},
 * and that was where the answer was changed. On 1.16.5 the equivalent,
 * {@code getTimeOfDay}, is a default method of the interface
 * {@code IDayTimeReader} and is not declared on any world class, so there is
 * no world method to inject into. What it does is one line — ask the dimension
 * to turn the day time into an angle — and that dimension method,
 * {@code DimensionType.timeOfDay(long)}, is a plain class method. So the
 * question is answered one step further in, by replacing the day time it is
 * handed.
 *
 * The sky, the light level, the fog colour, the stars and the sun direction
 * this mod sends the terrain shader all go through it, so answering there
 * moves them together.
 *
 * <h2>Why the angle and not the clock</h2>
 *
 * Answering at the world's day time would look like the tidier place and is a
 * trap: {@code ClientWorld.tickTime} runs
 * {@code setDayTime(levelData.getDayTime() + 1)} every tick, so a client that
 * read our invented hour back would write it into its own world info — the
 * clock would really stop, and switching the setting off would leave the world
 * at a time it was never at. The angle is read and never written back.
 *
 * It also leaves the moon phase alone, which is counted by
 * {@code DimensionType.moonPhase} from the day number rather than from the
 * angle, so moving the slider does not move the moon. And a dimension with a
 * fixed time — the Nether, the End — keeps it: {@code timeOfDay} prefers its
 * fixed time over the argument, so the replaced argument is simply ignored
 * there, as the real one was.
 *
 * <h2>Why this cannot reach the server</h2>
 *
 * A dimension type is shared by the server's world and the client's, so there
 * is no {@code isClientSide} to ask here. The thread is asked instead: the
 * integrated server ticks its worlds on a thread of its own, and only the
 * client's world is ever asked for its time of day on the render thread. Mobs
 * still burn at dawn, and beds still work at night, whatever this shows.
 */
@Mixin(DimensionType.class)
public abstract class WorldTimeDisplayMixin {

    @ModifyVariable(method = "timeOfDay", at = @At("HEAD"), argsOnly = true)
    private long vulkanmodnext$shownTime(long dayTime) {
        if (!WorldDisplay.overridingTime() || !RenderSystem.isOnRenderThread()) {
            return dayTime;
        }
        return WorldDisplay.worldTime(dayTime);
    }
}
