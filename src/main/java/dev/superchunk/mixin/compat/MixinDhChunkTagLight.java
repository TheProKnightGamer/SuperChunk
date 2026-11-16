package dev.superchunk.mixin.compat;

import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_forge;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.chunkFileHandling.ChunkCompoundTagParser$CombinedChunkLightStorage_forge;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.chunkFileHandling.CompoundTagUtil_forge;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.ChunkLightStorage;
import dev.superchunk.compat.DhFastPaths;
import dev.superchunk.compat.DhSelfCheck;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Distant Horizons copies the light of a saved chunk a section at a time instead of a block at a time.
 *
 * <p>For a world that already exists, DH builds its LODs from the region files, and
 * {@code ChunkCompoundTagParser.readLight} copies each chunk's saved block and sky light into DH's
 * own light storage: two {@code ChunkLightStorage.set} calls for every block of every section,
 * about 200,000 per chunk, each of which looks its section up, takes and releases the section's
 * concurrency-check lock, and updates a count. That was over 40% of the CPU of importing a world,
 * all of it on the one thread the chunk reads complete on.
 *
 * <p>The state DH's storage ends in depends only on the 4,096 values of a section: 256 packed
 * columns and a count per light level, or one constant when every block has the same level. This
 * writes that state for a whole section at once. Everything else is DH's code as written: the same
 * sections in the same order, DH's own treatment of a missing sky-light array, its error lines and
 * its early returns. A section that is not the plain case (outside the build height, an array of
 * the wrong length, a storage section that already holds something) goes through DH's own
 * {@code set} calls, block by block, with whatever they throw.
 *
 * <p>Applied when the DH methods this relies on hash to a checked version, under self-check when
 * they differ only in how they compute ({@link dev.superchunk.compat.DhFastPathTable}). Kill switch:
 * {@code -Dsuperchunk.dh.fastPaths=false}; {@code -Dsuperchunk.dh.lodVerify=true} also runs DH's own
 * copy for every chunk and compares the two storages.
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath.
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.chunkFileHandling.ChunkCompoundTagParser_forge", remap = false)
public abstract class MixinDhChunkTagLight {

    @Shadow
    @Final
    public static DhLogger LOGGER;

    @Shadow
    private static boolean lightingSectionErrorLogged;

    @Shadow
    public static ChunkCompoundTagParser$CombinedChunkLightStorage_forge readLight(ChunkAccess chunk, CompoundTag chunkData) {
        throw new AssertionError();
    }

    @Unique
    private static final boolean SUPERCHUNK$VERIFY = Boolean.getBoolean("superchunk.dh.lodVerify");

    @Unique
    private static final Logger SUPERCHUNK$LOG = LoggerFactory.getLogger("SuperChunk-Compat");

    /** Not null when this patch runs under self-check on the installed DH. */
    @Unique
    private static final DhSelfCheck SUPERCHUNK$LIGHT_CHECK = DhFastPaths.selfCheck("MixinDhChunkTagLight", "chunks");

    /** Set while DH's own copy runs for a comparison, which then must not take this path. */
    @Unique
    private static final ThreadLocal<Boolean> SUPERCHUNK$IN_DH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Unique
    private static final LongAdder SUPERCHUNK$CHECKED = new LongAdder();

    @Unique
    private static final LongAdder SUPERCHUNK$MISMATCHES = new LongAdder();

    @Unique
    private static final AtomicInteger SUPERCHUNK$REPORTED = new AtomicInteger();

    @Inject(method = "readLight", at = @At("HEAD"), cancellable = true)
    private static void superchunk$bySections(ChunkAccess chunk, CompoundTag chunkData, CallbackInfoReturnable<ChunkCompoundTagParser$CombinedChunkLightStorage_forge> cir) {
        DhSelfCheck check = SUPERCHUNK$LIGHT_CHECK;
        if (check != null && check.failed() || SUPERCHUNK$IN_DH.get()) {
            return;
        }
        ChunkCompoundTagParser$CombinedChunkLightStorage_forge fast;
        if (check == null) {
            fast = superchunk$read(chunk, chunkData);
        } else {
            try {
                fast = superchunk$read(chunk, chunkData);
            } catch (Throwable t) {
                check.fail("its code threw " + t, t);
                return;
            }
        }
        if (!SUPERCHUNK$VERIFY && (check == null || !check.sample())) {
            cir.setReturnValue(fast);
            return;
        }
        // Compared with DH's own copy, which is then the one used.
        ChunkCompoundTagParser$CombinedChunkLightStorage_forge own;
        SUPERCHUNK$IN_DH.set(Boolean.TRUE);
        try {
            own = readLight(chunk, chunkData);
        } finally {
            SUPERCHUNK$IN_DH.set(Boolean.FALSE);
        }
        String difference = superchunk$difference(fast, own);
        if (check != null) {
            if (difference == null) {
                check.passed();
            } else {
                check.fail("chunk " + chunk.getPos() + ": " + difference, null);
            }
        }
        if (SUPERCHUNK$VERIFY) {
            SUPERCHUNK$CHECKED.increment();
            if (difference != null) {
                SUPERCHUNK$MISMATCHES.increment();
                if (SUPERCHUNK$REPORTED.getAndIncrement() < 10) {
                    SUPERCHUNK$LOG.warn("[SuperChunk-Compat] [dh] saved light VERIFY MISMATCH at chunk {}: {}", chunk.getPos(), difference);
                }
            }
            if (SUPERCHUNK$CHECKED.sum() % 4096 == 0) {
                long m = SUPERCHUNK$MISMATCHES.sum();
                SUPERCHUNK$LOG.info("[SuperChunk-Compat] [dh] saved light VERIFY: checked={} mismatches={} -> {}",
                        SUPERCHUNK$CHECKED.sum(), m, m == 0 ? "PASS" : "FAIL");
            }
        }
        cir.setReturnValue(own);
    }

    /** DH's {@code readLight}, with each section's 4,096 pairs of {@code set} calls done at once (see the class comment). */
    @Unique
    private static ChunkCompoundTagParser$CombinedChunkLightStorage_forge superchunk$read(ChunkAccess chunk, CompoundTag chunkData) {
        ChunkCompoundTagParser$CombinedChunkLightStorage_forge combinedStorage = new ChunkCompoundTagParser$CombinedChunkLightStorage_forge(
                ChunkWrapper_forge.getInclusiveMinBuildHeight(chunk), ChunkWrapper_forge.getExclusiveMaxBuildHeight(chunk));
        ChunkLightStorage blockLightStorage = combinedStorage.blockLightStorage;
        ChunkLightStorage skyLightStorage = combinedStorage.skyLightStorage;
        boolean foundSkyLight = false;
        Tag chunkSectionTags = chunkData.get("sections");
        if (chunkSectionTags == null) {
            if (!lightingSectionErrorLogged) {
                lightingSectionErrorLogged = true;
                LOGGER.error("No sections found for chunk at pos [" + chunk.getPos() + "] chunk data may be out of date.");
            }
            return null;
        } else if (!(chunkSectionTags instanceof ListTag chunkSectionListTag)) {
            if (!lightingSectionErrorLogged) {
                lightingSectionErrorLogged = true;
                LOGGER.error("Chunk section tag list have unexpected type [" + chunkSectionTags.getClass().getName() + "], expected ["
                        + ListTag.class.getName() + "].");
            }
            return null;
        } else {
            for (int sectionIndex = 0; sectionIndex < chunkSectionListTag.size(); sectionIndex++) {
                Tag chunkSectionTag = chunkSectionListTag.get(sectionIndex);
                if (!(chunkSectionTag instanceof CompoundTag chunkSectionCompoundTag)) {
                    if (!lightingSectionErrorLogged) {
                        lightingSectionErrorLogged = true;
                        LOGGER.error("Chunk section tag has an unexpected type [" + chunkSectionTag.getClass().getName() + "], expected ["
                                + CompoundTag.class.getName() + "].");
                    }
                    return null;
                }
                byte[] blockLightNibbleArray = CompoundTagUtil_forge.getByteArray(chunkSectionCompoundTag, "BlockLight");
                byte[] skyLightNibbleArray = CompoundTagUtil_forge.getByteArray(chunkSectionCompoundTag, "SkyLight");
                if (blockLightNibbleArray != null && skyLightNibbleArray != null) {
                    if (skyLightNibbleArray.length != 0) {
                        foundSkyLight = true;
                    }
                    int bottomY = sectionIndex * 16 + ChunkWrapper_forge.getInclusiveMinBuildHeight(chunk);
                    int absentSkyLight = skyLightNibbleArray.length == 0 && foundSkyLight ? 15 : 0;
                    if (superchunk$whole(blockLightStorage, bottomY, blockLightNibbleArray)
                            && superchunk$whole(skyLightStorage, bottomY, skyLightNibbleArray)) {
                        superchunk$fill(blockLightStorage, bottomY, blockLightNibbleArray, 0);
                        superchunk$fill(skyLightStorage, bottomY, skyLightNibbleArray, absentSkyLight);
                    } else {
                        // Not the plain case: DH's own calls, in DH's order.
                        for (int relX = 0; relX < 16; relX++) {
                            for (int relZ = 0; relZ < 16; relZ++) {
                                for (int relY = 0; relY < 16; relY++) {
                                    int blockPosIndex = relY * 16 * 16 + relZ * 16 + relX;
                                    int blockLight = blockLightNibbleArray.length == 0 ? 0 : superchunk$nibble(blockLightNibbleArray, blockPosIndex);
                                    int skyLight = skyLightNibbleArray.length == 0 ? absentSkyLight : superchunk$nibble(skyLightNibbleArray, blockPosIndex);
                                    int y = relY + bottomY;
                                    blockLightStorage.set(relX, y, relZ, blockLight);
                                    skyLightStorage.set(relX, y, relZ, skyLight);
                                }
                            }
                        }
                    }
                }
            }
            return combinedStorage;
        }
    }

    /** DH's {@code getNibbleAtIndex}. */
    @Unique
    private static int superchunk$nibble(byte[] nibbles, int index) {
        return index % 2 == 0 ? nibbles[index / 2] & 15 : nibbles[index / 2] >> 4 & 15;
    }

    /**
     * Whether the 16 layers from {@code bottomY} are exactly one section of the storage, not yet
     * written, and the array (if any) has every nibble: the case {@link #superchunk$fill} handles.
     */
    @Unique
    private static boolean superchunk$whole(ChunkLightStorage storage, int bottomY, byte[] nibbles) {
        int height = storage.maxY - storage.minY;
        int offset = bottomY - storage.minY;
        if ((height & 15) != 0 || (offset & 15) != 0 || offset < 0 || offset + 16 > height) {
            return false;
        }
        if (nibbles.length != 0 && nibbles.length < 2048) {
            return false;
        }
        if (storage.lightSections == null) {
            return true;
        }
        int index = offset >> 4;
        if (index >= storage.lightSections.length) {
            return false;
        }
        ChunkLightStorage.LightSection section = storage.lightSections[index];
        return section == null || section.constantValue == 0 && section.data == null && section.counts != null
                && section.counts.length == 16 && section.counts[0] == 4096;
    }

    /**
     * The state 4,096 {@code ChunkLightStorage.set} calls leave a new section in: nothing changed
     * when every level is 0, the constant when every level is the same, otherwise the packed
     * columns and a count per level.
     */
    @Unique
    private static void superchunk$fill(ChunkLightStorage storage, int bottomY, byte[] nibbles, int absentValue) {
        if (storage.lightSections == null) {
            storage.lightSections = new ChunkLightStorage.LightSection[(storage.maxY - storage.minY) / 16];
        }
        int index = bottomY - storage.minY >> 4;
        ChunkLightStorage.LightSection section = storage.lightSections[index];
        if (section == null) {
            section = new ChunkLightStorage.LightSection(0);
            storage.lightSections[index] = section;
        }
        if (nibbles.length == 0) {
            if (absentValue != 0) {
                section.counts[0] = 0;
                section.counts[absentValue] = 4096;
                section.constantValue = (byte) absentValue;
            }
            return;
        }
        long[] data = new long[256];
        int[] counts = new int[16];
        for (int relZ = 0; relZ < 16; relZ++) {
            for (int relX = 0; relX < 16; relX++) {
                long bits = 0L;
                int column = relZ * 16 + relX;
                for (int relY = 0; relY < 16; relY++) {
                    int level = superchunk$nibble(nibbles, relY * 256 + column);
                    bits |= (long) level << (relY << 2);
                    counts[level]++;
                }
                data[relZ << 4 | relX] = bits;
            }
        }
        if (counts[0] == 4096) {
            return;
        }
        for (int level = 0; level < 16; level++) {
            section.counts[level] = (short) counts[level];
            if (counts[level] == 4096) {
                section.constantValue = (byte) level;
                return;
            }
        }
        section.data = data;
        section.constantValue = -1;
    }

    /** Where two results of {@code readLight} differ, or null when they are the same. */
    @Unique
    private static String superchunk$difference(ChunkCompoundTagParser$CombinedChunkLightStorage_forge a, ChunkCompoundTagParser$CombinedChunkLightStorage_forge b) {
        if (a == null || b == null) {
            return a == b ? null : "one is null";
        }
        String block = superchunk$difference(a.blockLightStorage, b.blockLightStorage);
        if (block != null) {
            return "block light " + block;
        }
        String sky = superchunk$difference(a.skyLightStorage, b.skyLightStorage);
        return sky == null ? null : "sky light " + sky;
    }

    @Unique
    private static String superchunk$difference(ChunkLightStorage a, ChunkLightStorage b) {
        if (a == null || b == null) {
            return a == b ? null : "storage missing";
        }
        if (a.minY != b.minY || a.maxY != b.maxY) {
            return "height";
        }
        if (a.lightSections == null || b.lightSections == null) {
            return a.lightSections == b.lightSections ? null : "sections missing";
        }
        if (a.lightSections.length != b.lightSections.length) {
            return "section count";
        }
        for (int i = 0; i < a.lightSections.length; i++) {
            ChunkLightStorage.LightSection x = a.lightSections[i];
            ChunkLightStorage.LightSection y = b.lightSections[i];
            if (x == null || y == null) {
                if (x != y) {
                    return "section " + i + " missing";
                }
                continue;
            }
            if (x.constantValue != y.constantValue || !Arrays.equals(x.counts, y.counts) || !Arrays.equals(x.data, y.data)) {
                return "section " + i;
            }
        }
        return null;
    }
}
