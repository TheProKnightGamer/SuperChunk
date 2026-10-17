package dev.superchunk.com.ishland.c2me.opts.allocs.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

@Mixin(targets = "net.minecraft.nbt.ListTag$1")
public class MixinNbtList1 {

    // 1.20.1: ListTag$1.load(...) is an INSTANCE method of the anonymous TagType (1.21.1
    // made it effectively static via the reader-record refactor) — handler modifiers must
    // match the target, so these are instance methods here.
    @ModifyVariable(method = "Lnet/minecraft/nbt/ListTag$1;load(Ljava/io/DataInput;ILnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/ListTag;", at = @At(value = "INVOKE_ASSIGN", target = "Lcom/google/common/collect/Lists;newArrayListWithCapacity(I)Ljava/util/ArrayList;", remap = false))
    private List<Tag> modifyList(List<Tag> list) {
        return new ObjectArrayList<>();
    }

    @Redirect(method = "Lnet/minecraft/nbt/ListTag$1;load(Ljava/io/DataInput;ILnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/ListTag;", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Lists;newArrayListWithCapacity(I)Ljava/util/ArrayList;", remap = false))
    private <E> ArrayList<E> redirectNewArrayList(int initialArraySize) {
        return null;
    }

}
