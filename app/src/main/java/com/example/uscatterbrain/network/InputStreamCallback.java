package com.example.uscatterbrain.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import io.reactivex.disposables.Disposable;

/**
 * This is a somewhat hacky brige between RxJava2 streams and a classic
 * inputstream. It buffers any data observed as an observer and
 * replays it when read from as an inputstream.
 */
public abstract class InputStreamCallback extends InputStream {

    private final int BUF_CAPACITY;
    protected Throwable throwable;
    protected boolean closed = false;
    protected Disposable disposable;
    private final CircularBuffer buf;
    private boolean complete = false;

    public InputStreamCallback() {
        this(524288);
    }

    public InputStreamCallback(int capacity) {
        this.BUF_CAPACITY = capacity;
        buf = new CircularBuffer(ByteBuffer.allocate(BUF_CAPACITY + 1));
    }

    protected void acceptBytes(byte[] val) {
        buf.put(val, 0, Math.min(val.length, buf.remaining()));
        synchronized (buf) {
            buf.notifyAll();
        }
    }

    public int size() {
        return buf.size();
    }

    protected void complete() {
        complete = true;
        synchronized (buf) {
            buf.notifyAll();
        }
    }

    private int get(byte[] result, int offset, int len) throws IOException {
        if (throwable != null) {
            throwable.printStackTrace();
        }

        if (closed) {
            throw new IOException("closed");
        }

        if (complete && buf.size() == 0) {
            return -1;
        }

        synchronized (buf) {
            try {
                while (buf.size() == 0) {
                    buf.wait();
                }
                int l = Math.min(len, buf.size());
                buf.get(result, offset, l);
                return l;
            } catch (BufferUnderflowException ignored) {
                throw new IOException("underflow");
            } catch (InterruptedException ignored) {
                return -1;
            }
        }
    }

    @Override
    public int read(byte[] b) throws IOException {
        return get(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return get(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        synchronized (buf) {
            if (n >= Integer.MAX_VALUE || n <= Integer.MIN_VALUE) {
                throw new IOException("index out of range");
            }
            long skip = 0;
            return buf.skip(n);
        }
    }

    @Override
    public int available() throws IOException {
        return buf.remaining();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        synchronized (buf) {
            buf.notifyAll();
        }
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Override
    public synchronized void mark(int readlimit) {
        super.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
        super.reset();
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public int read() throws IOException {
        if (closed) {
            throw new IOException("closed");
        }

        if (complete && buf.size() == 0) {
            return -1;
        }

        synchronized (buf) {
            try {
                while (buf.size() == 0) {
                     buf.wait();
                }
                return buf.get();
            } catch (InterruptedException ignored) {
                return -1;
            }
        }
    }
}
