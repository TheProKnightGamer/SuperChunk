package dev.superchunk.net.caffeinemc.mods.lithium.mixin.ai.task.replace_streams;

import dev.superchunk.net.caffeinemc.mods.lithium.common.ai.WeightedListIterable;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Iterator;
import java.util.List;

@Mixin(ShufflingList.class)
public class ShufflingListMixin<U> implements WeightedListIterable<U> {
    @Shadow
    @Final
    // PORT-1.20.1: shadow generic is List<WeightedEntry<U>> (1.21 widened it to ? extends U).
    protected List<ShufflingList.WeightedEntry<U>> entries;

    @Override
    public Iterator<U> iterator() {
        return new ListIterator<>(this.entries.iterator());
    }
}
