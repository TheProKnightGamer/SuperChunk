package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.common;

import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;

/**
 * Duck implanted on {@link StrongholdPieces} by {@code MixinStrongholdGenerator}: exposes
 * the thread-local replacement of the vanilla static {@code imposedPiece} so the
 * {@code StairsDown.addChildren} redirect ({@code MixinStrongholdGeneratorSpiralStaircase})
 * writes the SAME per-thread slot the piece picker reads. Faithful mojmap port of upstream
 * C2ME's IStrongholdGenerator (yarn StrongholdGenerator = mojmap StrongholdPieces).
 */
public interface IStrongholdGenerator {

    ThreadLocal<Class<? extends StrongholdPieces.StrongholdPiece>> getActivePieceTypeThreadLocal();

    class Holder {
        @SuppressWarnings({"InstantiationOfUtilityClass", "ConstantConditions"})
        public static final IStrongholdGenerator INSTANCE = (IStrongholdGenerator) new StrongholdPieces();
    }

}
