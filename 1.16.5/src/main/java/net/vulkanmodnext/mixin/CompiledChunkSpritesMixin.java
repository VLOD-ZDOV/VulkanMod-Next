package net.vulkanmodnext.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.vulkanmodnext.client.AnimatedSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Which animated sprites a compiled section's blocks use; see
 * {@link AnimatedSprites}. Null until a build recorded it, which is what the
 * never-built placeholder and everything built with the setting off keep —
 * and null reads as "uses everything", never as "uses nothing".
 */
@Mixin(ChunkRenderDispatcher.CompiledChunk.class)
public abstract class CompiledChunkSpritesMixin implements AnimatedSprites.SpriteMarked {

    @Unique
    private volatile long[] vulkanmodnext$sprites;

    @Override
    public long[] vulkanmodnext$animatedSprites() {
        return this.vulkanmodnext$sprites;
    }

    @Override
    public void vulkanmodnext$animatedSprites(long[] mask) {
        this.vulkanmodnext$sprites = mask;
    }
}
