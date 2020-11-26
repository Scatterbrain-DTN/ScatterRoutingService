package com.example.uscatterbrain.network;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

public class CircularBuffer {
    private final ByteBuffer writeBuffer;
    private final ByteBuffer readBuffer;
    public CircularBuffer(ByteBuffer byteBuffer) {
        this.writeBuffer = byteBuffer;
        this.readBuffer = byteBuffer.duplicate();
    }

    public int size() {
        return (writeBuffer.position() - readBuffer.position()) % writeBuffer.capacity();
    }

    public byte get() {
        if (readBuffer.remaining() == 0) {
            readBuffer.position(0);
        }
        return readBuffer.get();
    }

    public void get(byte[] val, int offset, int length) {
        int l = length;

        if (l > size()) {
            throw new BufferOverflowException();
        }

        int read = Math.min(length, readBuffer.remaining());
        readBuffer.get(val, offset, read);
        l -= read;
        if (l > 0) {
            readBuffer.position(0);
            readBuffer.get(val, offset, l);
        }
    }

    public void put(byte[] val, int offset, int len) {
        int l = len;

        if (l > remaining()) {
            throw new BufferOverflowException();
        }

        int write = Math.min(len, writeBuffer.remaining());
        writeBuffer.put(val, offset, write);
        l -= write;
        if (l > 0) {
            writeBuffer.position(0);
            writeBuffer.put(val, offset, l);
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
