package dev.superchunk.mixin.compat;

import dev.superchunk.compat.UnsyncByteArrayInputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.ByteArrayInputStream;

/**
 * Distant Horizons reads its stored LOD sections without a lock per byte.
 *
 * <p>Every time DH loads a section from its database (to update a parent detail level, among
 * others), it decompresses the blob and reads it back varint by varint, one {@code readByte} at a
 * time, through a {@code ByteArrayInputStream} it creates for that one read. The JDK's
 * {@code read()} is {@code synchronized}, so each byte took a monitor: about 2% of DH's world-gen
 * CPU on a pregen. The stream never leaves the thread that made it, so an unsynchronized copy of
 * the same two read methods ({@link UnsyncByteArrayInputStream}) gives the same bytes.
 *
 * <p>Applied only when DH's factory method hashes to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch: {@code -Dsuperchunk.dh.fastPaths=false}.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream", remap = false)
public abstract class MixinDhDataInputStream {

    /** The byte[] overload, where the streams are made (a bare "create" selects the first overload only). */
    @org.spongepowered.asm.mixin.Unique
    private static final String SUPERCHUNK$CREATE = "create([BLcom/seibel/distanthorizons/api/enums/config/EDhApiDataCompressionMode;"
            + "Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;)"
            + "Lcom/seibel/distanthorizons/core/util/objects/dataStreams/DhDataInputStream;";

    @Redirect(method = SUPERCHUNK$CREATE, at = @At(value = "NEW", target = "([BII)Ljava/io/ByteArrayInputStream;"))
    private static ByteArrayInputStream superchunk$unsyncSlice(byte[] buf, int offset, int length) {
        return new UnsyncByteArrayInputStream(buf, offset, length);
    }

    @Redirect(method = SUPERCHUNK$CREATE, at = @At(value = "NEW", target = "([B)Ljava/io/ByteArrayInputStream;"))
    private static ByteArrayInputStream superchunk$unsync(byte[] buf) {
        return new UnsyncByteArrayInputStream(buf);
    }
}
