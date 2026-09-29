package net.vulkanmodnext.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.vector.Vector3d;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets the clouds take the colour of the sky they are hanging in.
 *
 * <h2>What vanilla does</h2>
 *
 * {@code ClientWorld.getCloudColor} returns white, darkened for night and for
 * rain, and that is all it knows. At sunset the sky below the clouds turns
 * orange and the clouds stay the colour they were at noon — which is the one
 * thing about a vanilla sky that reads as wrong rather than as plain, because a
 * cloud is lit by the same sun as everything else and is the last thing that
 * sun reaches.
 *
 * <h2>What this does instead</h2>
 *
 * Mixes in two colours the game has already worked out for this exact moment:
 * the sky colour, which carries the biome and the weather, and the sunrise and
 * sunset colours, which the dimension computes for the band around the horizon
 * and which are non-null only while there is a sunset to have. Nothing is
 * invented — this is the game's own palette applied to a surface it was never
 * applied to, which is why it cannot disagree with the sky behind it.
 *
 * <h2>Why the colour, and not the drawing</h2>
 *
 * On 1.16.5 the clouds are built once into a vertex buffer with this colour
 * baked into every vertex, and rebuilt only when the camera crosses a cloud
 * cell or the colour returned here moves by more than a sliver. So the answer
 * given here is exactly what that comparison sees: a sunset that turns the
 * clouds rebuilds them at the same rate night falling already does, and a
 * slider moved rebuilds them once. Nothing else needs telling.
 *
 * <h2>Why the redirect names ClientWorld</h2>
 *
 * The call is made on {@code WorldRenderer.level}, declared
 * {@code ClientWorld}, and {@code getCloudColor} is declared there too — so
 * the invoke names {@code ClientWorld}, and a redirect aimed anywhere else
 * matches nothing. That exact mistake cost the 1.12.2 build a release with a
 * hook that was never attached and said nothing about it; here it would stop
 * the game at startup instead, which is why every injection is required to
 * find its target.
 *
 * <h2>What it costs</h2>
 *
 * One call a frame, on the line that already asks for the cloud colour once.
 * A dimension with a Forge {@code ICloudRenderHandler} returns before that line
 * and keeps its own clouds.
 */
@Mixin(WorldRenderer.class)
public abstract class CloudTintMixin {

    @Redirect(method = "renderClouds",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/world/ClientWorld;"
                            + "getCloudColor(F)Lnet/minecraft/util/math/vector/Vector3d;"))
    private Vector3d vulkanmodnext$tintClouds(ClientWorld world, float partialTicks) {
        Vector3d colour = world.getCloudColor(partialTicks);
        int strength = VulkanConfig.get("cloudTint");
        if (strength <= 0) {
            return colour;
        }
        try {
            return tint(world, colour, strength / 100.0f, partialTicks);
        } catch (Throwable t) {
            // A mod with its own sky, or a frame without a camera. The vanilla
            // colour is always an answer.
            return colour;
        }
    }

    private static Vector3d tint(ClientWorld world, Vector3d colour, float amount,
                                 float partialTicks) {
        double r = colour.x;
        double g = colour.y;
        double b = colour.z;
        // The sky's own colour, asked where renderSky asks it — at the camera —
        // so it already carries the biome, the time and the weather. Half
        // weight: clouds are lit by the sky rather than made of it, and at full
        // weight they stop being white at noon, which is the one thing about
        // them nobody wants changed.
        Vector3d sky = world.getSkyColor(
                Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition(),
                partialTicks);
        double w = amount * 0.5;
        r += (sky.x - r) * w;
        g += (sky.y - g) * w;
        b += (sky.z - b) * w;
        // And the sunset, which is the whole point. The dimension computes this
        // for the band renderSky paints around the horizon and returns null
        // whenever there is no sunrise or sunset happening, so this costs
        // nothing for most of the day and needs no clock of its own. The fourth
        // component is how much of it the sky itself is using, so following it
        // is what keeps the clouds turning at the same rate the horizon does.
        float[] sunset = world.effects().getSunriseColor(
                world.getTimeOfDay(partialTicks), partialTicks);
        if (sunset != null) {
            double s = amount * sunset[3];
            r += (sunset[0] - r) * s;
            g += (sunset[1] - g) * s;
            b += (sunset[2] - b) * s;
        }
        return new Vector3d(r, g, b);
    }
}
