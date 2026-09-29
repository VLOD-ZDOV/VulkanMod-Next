package net.vulkanmodnext.mixin;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockDisplayReader;
import net.minecraftforge.client.model.data.IModelData;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.vulkanmodnext.client.AnimatedSprites;
import net.vulkanmodnext.client.MaterialRuns;
import net.vulkanmodnext.client.VulkanConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;
import java.util.Set;

/**
 * Records what each stretch of a chunk's geometry is made of, while the chunk
 * is being built and the answer is still knowable.
 *
 * See {@link MaterialRuns} for why the information exists nowhere else. The
 * 1.12.2 twin has one call to wrap; here there are two, because this version
 * draws a block's fluid with a call of its own — and a waterlogged block is
 * two materials in one position, which the single call could never have said.
 *
 * Where a layer's buffer starts over is {@code ChunkRender.beginLayer}, a
 * method of the outer class; see {@link MaterialLayerMixin}.
 *
 * Both redirects call straight through when the setting is off, a predicted
 * branch on a call that already dispatches through a block model.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$ChunkRender$RebuildTask")
public abstract class MaterialTagMixin {

    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/BlockRendererDispatcher;renderModel("
                    + "Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/world/IBlockDisplayReader;"
                    + "Lcom/mojang/blaze3d/matrix/MatrixStack;"
                    + "Lcom/mojang/blaze3d/vertex/IVertexBuilder;ZLjava/util/Random;"
                    + "Lnet/minecraftforge/client/model/data/IModelData;)Z"))
    private boolean vulkanmodnext$tagBlock(BlockRendererDispatcher dispatcher, BlockState state,
                                          BlockPos pos, IBlockDisplayReader world,
                                          MatrixStack matrices, IVertexBuilder builder,
                                          boolean cull, Random random, IModelData data) {
        boolean drew = dispatcher.renderModel(state, pos, world, matrices, builder, cull,
                random, data);
        // Smart animations share this call rather than wrapping it a second
        // time: two redirects on one call site cannot both apply.
        AnimatedSprites.recordBlock(state);
        if (VulkanConfig.on("materialTags") && builder instanceof BufferBuilder) {
            // Read after the call, and for every block rather than only the
            // ones that drew: a block that added nothing still ends where the
            // one before it ended, and the buffer is what knows where that is.
            BufferBuilder buffer = (BufferBuilder) builder;
            MaterialRuns.record(state, buffer,
                    ((BufferBuilderAccess) buffer).vulkanmodnext$vertices());
        }
        return drew;
    }

    @Redirect(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/BlockRendererDispatcher;renderLiquid("
                    + "Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/world/IBlockDisplayReader;"
                    + "Lcom/mojang/blaze3d/vertex/IVertexBuilder;"
                    + "Lnet/minecraft/fluid/FluidState;)Z"))
    private boolean vulkanmodnext$tagFluid(BlockRendererDispatcher dispatcher, BlockPos pos,
                                          IBlockDisplayReader world, IVertexBuilder builder,
                                          FluidState fluid) {
        boolean drew = dispatcher.renderLiquid(pos, world, builder, fluid);
        AnimatedSprites.recordFluid(fluid, world, pos);
        if (VulkanConfig.on("materialTags") && builder instanceof BufferBuilder) {
            BufferBuilder buffer = (BufferBuilder) builder;
            MaterialRuns.recordFluid(fluid, buffer,
                    ((BufferBuilderAccess) buffer).vulkanmodnext$vertices());
        }
        return drew;
    }

    /**
     * A chunk build starts: smart animations begin recording which animated
     * sprites it uses. Only when that is on — a record costs an array per
     * chunk, and without one the calls above return at their first test.
     */
    @Inject(method = "compile", at = @At("HEAD"))
    private void vulkanmodnext$beginSprites(float cameraX, float cameraY, float cameraZ,
                                           ChunkRenderDispatcher.CompiledChunk compiled,
                                           RegionRenderCacheBuilder buffers,
                                           CallbackInfoReturnable<Set<TileEntity>> cir) {
        if (VulkanConfig.on("smartAnimations")) {
            AnimatedSprites.beginChunk();
        }
    }

    /**
     * The record goes on the compiled result, not on the section: the result
     * is what replaces the old one once its geometry has uploaded, so the
     * record and the geometry it describes are drawn from together.
     */
    @Inject(method = "compile", at = @At("RETURN"))
    private void vulkanmodnext$finishSprites(float cameraX, float cameraY, float cameraZ,
                                            ChunkRenderDispatcher.CompiledChunk compiled,
                                            RegionRenderCacheBuilder buffers,
                                            CallbackInfoReturnable<Set<TileEntity>> cir) {
        long[] mask = AnimatedSprites.finishChunk();
        if (mask != null) {
            ((AnimatedSprites.SpriteMarked) compiled).vulkanmodnext$animatedSprites(mask);
        }
    }
}
