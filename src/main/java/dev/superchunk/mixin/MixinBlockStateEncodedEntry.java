package dev.superchunk.mixin;

import dev.superchunk.chunkio.DirectPaletteEncoder;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Each block state's palette strings for {@link DirectPaletteEncoder}, built the first time the state is saved. */
@Mixin(BlockState.class)
public abstract class MixinBlockStateEncodedEntry implements DirectPaletteEncoder.EncodedEntryHolder {
    /** An immutable entry (or the "unnamed" marker); a racing thread can only store an equal one. */
    @Unique
    private Object superchunk$encodedEntry;

    @Override
    public Object superchunk$encodedEntry() {
        return this.superchunk$encodedEntry;
    }

    @Override
    public void superchunk$setEncodedEntry(Object entry) {
        this.superchunk$encodedEntry = entry;
    }
}
