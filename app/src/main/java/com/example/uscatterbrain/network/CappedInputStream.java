package com.example.uscatterbrain.network;

import java.io.IOException;
import java.io.InputStream;

/**
 * Since protobuf's api for reading streams into bytebuffers lacks
 * the ability to read partial streams, this hack should
 * trick it into not devouring the entire inputstream
 */
public class CappedInputStream extends InputStream {
    private InputStream mStream;
    private int mRemaining;
    public CappedInputStream(InputStream stream, int cap) {
        super();
        mRemaining = cap;
        mStream = stream;
    }

    @Override
    public int read(byte[] b) throws IOException {
        if (mRemaining <= 0)
            return -1;

        int bytesread = mStream.read(b, 0, mRemaining);
        mRemaining -= bytesread;
        return bytesread;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (mRemaining <= 0)
            return -1;
        int l = mRemaining;

        if (mRemaining + off > b.length)
            l = b.length - off;

        int bytesread = mStream.read(b, off, l);
        mRemaining -= bytesread;
        return  bytesread;
    }

    @Override
    public long skip(long n) throws IOException {
        return mStream.skip(n);
    }

    @Override
    public int available() throws IOException {
        return Math.min(mStream.available(), mRemaining);
    }

    @Override
    public void close() throws IOException {
        mStream.close();
    }

    @Override
    public synchronized void mark(int readlimit) {
        mStream.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
        mStream.reset();
    }

    @Override
    public boolean markSupported() {
        return mStream.markSupported();
    }

    @Override
    public int read() throws IOException {
        if (mRemaining > 0) {
            int val = mStream.read();
            mRemaining--;
            return val;
        } else {
            return -1;
        }
    }
}
