package dev.superchunk.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

/**
 * SuperChunk: section-direct {@code Heightmap.primeHeightmaps} (runs twice per chunk —
 * at surface prime and again at full status).
 *
 * <p><b>Why.</b> Vanilla descends each of the 256 columns calling
 * {@code chunk.getBlockState(mutablePos)} PER BLOCK — full bounds-check + section-index
 * + {@code hasOnlyAir} + palette decode — including through long air runs above terrain.
 *
 * <p><b>What changes (and what must NOT).</b> ONLY the y-descent's block source:
 * sections are hoisted per 16-y run; {@code hasOnlyAir()} sections are skipped wholesale
 * (for a ProtoChunk, vanilla's read returns the canonical AIR state there, which fails
 * the {@code !state.is(AIR)} test → provably zero iterator interactions and zero
 * writes); non-empty sections read {@code section.getBlockState(x, y&15, z)} — the exact
 * value vanilla's ProtoChunk read resolves to for in-bounds y. Everything else,
 * INCLUDING vanilla's deliberately quirky growing-list/iterator/{@code back(i)}
 * machinery (it has observable stateful semantics), is replicated verbatim. All reads
 * are in-bounds by construction (scan starts at highestSectionPosition+15).
 *
 * <p>Exact-class {@code ProtoChunk} gate — {@code LevelChunk} (debug-world branch) and
 * {@code ImposterProtoChunk}/modded chunks fall through to vanilla.
 * Disable with {@code -Dsuperchunk.worldgen.primeHeightmapsFast=false}.
 */
@Mixin(Heightmap.class)
public abstract class MixinHeightmapPrimeFast {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.primeHeightmapsFast", "true"));

    // @WrapMethod rather than a HEAD-cancel @Inject: same fast-path effect, but the
    // fallthrough paths compose with other mods' wraps/overwrites of primeHeightmaps
    // instead of pre-empting them at HEAD.
    @WrapMethod(method = "primeHeightmaps", require = 0)
    private static void superchunk$primeFast(ChunkAccess chunk, Set<Heightmap.Types> types, Operation<Void> original) {
        if (!SUPERCHUNK$ENABLED || chunk.getClass() != ProtoChunk.class) {
            original.call(chunk, types);
            return;
        }
        int j0 = chunk.getHighestSectionPosition() + 16;
        if (j0 > chunk.getMaxBuildHeight()) {
            // pathological (non-16-aligned modded height accessor): vanilla would iterate
            // VOID_AIR rows WITH iterator side effects — let vanilla handle it verbatim
            original.call(chunk, types);
            return;
        }
        int i = types.size();
        ObjectList<Heightmap> objectlist = new ObjectArrayList<>(i);
        ObjectListIterator<Heightmap> iterator = objectlist.iterator();
        int j = j0;
        int minBuild = chunk.getMinBuildHeight();

        for (int k = 0; k < 16; k++) {
            for (int l = 0; l < 16; l++) {
                for (Heightmap.Types type : types) {
                    objectlist.add(chunk.getOrCreateHeightmapUnprimed(type));
                }

                int y = j - 1;
                columnDone:
                while (y >= minBuild) {
                    int sIdx = chunk.getSectionIndex(y);
                    LevelChunkSection sec = chunk.getSection(sIdx);
                    int secBottom = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                    if (sec.hasOnlyAir()) {
                        // whole run reads as canonical AIR in vanilla -> zero effect; skip it
                        y = secBottom - 1;
                        continue;
                    }
                    int yStop = Math.max(secBottom, minBuild);
                    for (; y >= yStop; y--) {
                        BlockState blockstate = sec.getBlockState(k, y & 15, l);
                        if (!blockstate.is(Blocks.AIR)) {
                            while (iterator.hasNext()) {
                                Heightmap heightmap = iterator.next();
                                if (((IHeightmapAccess) heightmap).superchunk$isOpaque().test(blockstate)) {
                                    ((IHeightmapAccess) heightmap).superchunk$setHeight(k, l, y + 1);
                                    iterator.remove();
                                }
                            }

                            if (objectlist.isEmpty()) {
                                break columnDone;
                            }

                            iterator.back(i);
                        }
                    }
                }
            }
        }
    }
}
