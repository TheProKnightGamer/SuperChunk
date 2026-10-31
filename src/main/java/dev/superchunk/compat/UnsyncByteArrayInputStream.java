package dev.superchunk.compat;

import java.io.ByteArrayInputStream;
import java.util.Objects;

/**
 * A {@link ByteArrayInputStream} whose two read methods are not {@code synchronized}, for streams
 * that one thread creates and reads. Same results as the JDK's, without a monitor per byte.
 */
public final class UnsyncByteArrayInputStream extends ByteArrayInputStream {

    public UnsyncByteArrayInputStream(byte[] buf) {
        super(buf);
    }

    public UnsyncByteArrayInputStream(byte[] buf, int offset, int length) {
        super(buf, offset, length);
    }

    @Override
    public int read() {
        return pos < count ? buf[pos++] & 0xff : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if (pos >= count) {
            return -1;
        }
        int avail = count - pos;
        if (len > avail) {
            len = avail;
        }
        if (len <= 0) {
            return 0;
        }
        System.arraycopy(buf, pos, b, off, len);
        pos += len;
        return len;
    }
}
