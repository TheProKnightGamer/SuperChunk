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

    @ModifyVariable(method = "Lnet/minecraft/nbt/ListTag$1;loadList(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/ListTag;", at = @At(value = "INVOKE_ASSIGN", target = "Lcom/google/common/collect/Lists;newArrayListWithCapacity(I)Ljava/util/ArrayList;", remap = false))
    private static List<Tag> modifyList(List<Tag> list) {
        return new ObjectArrayList<>();
    }

    @Redirect(method = "Lnet/minecraft/nbt/ListTag$1;loadList(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/ListTag;", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Lists;newArrayListWithCapacity(I)Ljava/util/ArrayList;", remap = false))
    private static <E> ArrayList<E> redirectNewArrayList(int initialArraySize) {
        return null;
    }

}
