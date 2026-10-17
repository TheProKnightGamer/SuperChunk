package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.collisions.intersection;

import com.google.common.collect.ImmutableList;
import dev.superchunk.net.caffeinemc.mods.lithium.common.world.WorldHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
import java.util.function.Predicate;

/**
 * PORT-1.20.1: neo redirects {@code getEntities} inside the {@code EntityGetter}
 * INTERFACE default method — runtime mixin 0.8.5 does not support {@code @Redirect} in
 * interface mixins at all ({@code InvalidInterfaceMixinException}). Upstream's own
 * 1.20.1 build (radium-0.12.4 {@code intersection.WorldMixin}, bytecode-verified) ships
 * this as a CLASS mixin on {@code Level} soft-overriding the inherited default method;
 * this mirrors that shape. Body is the verbatim 1.20.1 {@code getEntityCollisions}
 * (quoted from the 47.4.0 mapped sources) with the one lithium substitution:
 * {@code this.getEntities(...)} → {@code WorldHelper.getOtherEntitiesForCollision(...)}.
 * Soft-override: no runtime flag possible — gated at load time by this rule's config,
 * same convention as the InlineHeights pair (see SuperChunkMixinPlugin).
 */
@Mixin(Level.class)
public abstract class LevelMixin implements EntityGetter {

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        // [VanillaCopy] EntityGetter.getEntityCollisions (1.20.1), lithium retrieval swapped in
        if (box.getSize() < 1.0E-7D) {
            return List.of();
        }
        Predicate<Entity> predicate = entity == null
                ? EntitySelector.CAN_BE_COLLIDED_WITH
                : EntitySelector.NO_SPECTATORS.and(entity::canCollideWith);
        List<Entity> list = WorldHelper.getOtherEntitiesForCollision(this, box.inflate(1.0E-7D), entity, predicate);
        if (list.isEmpty()) {
            return List.of();
        }
        ImmutableList.Builder<VoxelShape> builder = ImmutableList.builderWithExpectedSize(list.size());
        for (Entity entity1 : list) {
            builder.add(Shapes.create(entity1.getBoundingBox()));
        }
        return builder.build();
    }
}
