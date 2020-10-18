package com.example.uscatterbrain.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Stack;

import io.reactivex.disposables.Disposable;

/**
 * This is a somewhat hacky brige between RxJava2 streams and a classic
 * inputstream. It buffers any data observed as an observer and
 * replays it when read from as an inputstream.
 */
public abstract class InputStreamCallback extends InputStream {

    private static final int BUF_CAPACITY = 524288;
    private final Stack<ByteBuffer> observableByteBuffers = new Stack<>();
    private final Deque<ByteBuffer> inputStreamBuffers = new ArrayDeque<>();
    protected Throwable throwable;
    protected boolean closed = false;
    protected Disposable disposable;

    public InputStreamCallback() {
        super();
    }

    private void allocateBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(BUF_CAPACITY);
        observableByteBuffers.push(buf);
        inputStreamBuffers.push(buf.duplicate());
    }

    private ByteBuffer getBuffer() {
        if (observableByteBuffers.size() == 0) {
            allocateBuffer();
        }
        return observableByteBuffers.peek();
    }

    protected void acceptBytes(byte[] val) {
        synchronized (observableByteBuffers) {
            int bufcount = val.length / BUF_CAPACITY;
            getBuffer().put(val, 0, Math.min(val.length, BUF_CAPACITY));
            for (int i = 0; i < bufcount; i++) {
                allocateBuffer();
                int offset = i * BUF_CAPACITY;
                int len = Math.min(val.length, BUF_CAPACITY);
                getBuffer().put(val, offset, len);
            }
        }
    }

    public int size() {
        if (observableByteBuffers.size() == 0) {
            return 0;
        } else {
            return BUF_CAPACITY * (observableByteBuffers.size() - 1) + observableByteBuffers.peek().position();
        }
    }

    private int get(byte[] result, int offset, int len) throws IOException{
        if (throwable != null) {
            throw new IOException(throwable.getMessage());
        }

        if (closed) {
            throw new IOException("closed");
        }


        if (inputStreamBuffers.size() == 0) {
            return 0;
        }

        synchronized (observableByteBuffers) {
            try {
                int read = 0;
                int l = Math.min(len, BUF_CAPACITY);
                int bufcount = len / BUF_CAPACITY;
                inputStreamBuffers.peekLast().get(result, offset, l);
                read += l;
                for (int i = 0; i < bufcount; i++) {
                    inputStreamBuffers.removeLast();
                    int offs = i * BUF_CAPACITY;
                    l = Math.min(len, BUF_CAPACITY);
                    inputStreamBuffers.peekLast().get(result, offset + offs, l);
                    read += l;
                }
                return read;
            } catch (BufferUnderflowException ignored) {
                throw new IOException("underflow");
            }
        }
    }

    @Override
    public int read(byte[] b) throws IOException {
        return get(b,0,b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return get(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        synchronized (observableByteBuffers) {
            if (n >= Integer.MAX_VALUE || n <= Integer.MIN_VALUE) {
                throw new IOException("index out of range");
            }
            long skip = 0;
            int index = (int) n;
            long bufcount = n / BUF_CAPACITY;
            for (int i = 0; i < bufcount; i++) {
                observableByteBuffers.pop();
                skip += BUF_CAPACITY;
            }
            int newpos = getBuffer().position() + Math.min(index, getBuffer().remaining());
            getBuffer().position(newpos);
            skip += newpos;
            return skip;
        }
    }

    @Override
    public int available() throws IOException {
        return getBuffer().remaining() + BUF_CAPACITY * (observableByteBuffers.size()-1);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (disposable != null) {
            synchronized (disposable) {
                disposable.dispose();
            }
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
        synchronized (observableByteBuffers) {
            if (getBuffer().remaining() > 0) {
                return getBuffer().get();
            } else if (observableByteBuffers.size() > 1) {
                observableByteBuffers.pop();
                return getBuffer().get();
            } else {
                return -1;
            }
        }
    }
}
