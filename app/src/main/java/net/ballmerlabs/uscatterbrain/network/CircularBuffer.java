package net.ballmerlabs.uscatterbrain.network;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

public class CircularBuffer {
    private final ByteBuffer writeBuffer;
    private final ByteBuffer readBuffer;
    public CircularBuffer(ByteBuffer byteBuffer) {
        this.writeBuffer = byteBuffer;
        this.readBuffer = byteBuffer.duplicate();
    }

    private static int mod(int a, int b) {
        if (b > 0) {
            int m = (m = a % b) < 0 ? a + b : a;
            return m;
        } else {
            throw new ArithmeticException();
        }
    }

    public int size() {
        return mod(writeBuffer.position() - readBuffer.position(), writeBuffer.capacity());
    }

    public byte get() {
        if (readBuffer.remaining() == 0) {
            readBuffer.position(0);
        }
        return readBuffer.get();
    }

    public void get(byte[] val, int offset, int length) {
        if (length > size()) {
            throw new BufferOverflowException();
        }

        int l = length;
        int read = Math.min(length, readBuffer.remaining());
        readBuffer.get(val, offset, read);
        l -= read;
        if (l > 0) {
            readBuffer.position(0);
            readBuffer.get(val, offset+read, l);
        }
    }

    public void put(byte[] val, int offset, int len) {
        int l = Math.min(val.length, len);

        if (l > remaining()) {
            throw new BufferOverflowException();
        }

        int write = Math.min(l, writeBuffer.remaining());
        writeBuffer.put(val, offset, write);
        l -= write;
        if (l > 0) {
            writeBuffer.position(0);
            writeBuffer.put(val, offset+write, l);
        }
    }

    public int remaining() {
        return writeBuffer.capacity() - size();
    }

    public long skip(long n) {
        int skip = (int) Math.min(remaining(), n);
        int p = readBuffer.position();
        readBuffer.position(readBuffer.position() + skip);
        return readBuffer.position() - p;
    }
}
