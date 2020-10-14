package com.example.uscatterbrain.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Stack;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

/**
 * This is a somewhat hacky brige between RxJava2 streams and a classic
 * inputstream. It buffers any data observed as an observer and
 * replays it when read from as an inputstream.
 */
public class InputStreamObserver extends InputStream implements Observer<byte[]> {

    private static final int BUF_CAPACITY = 524288;
    private final Stack<ByteBuffer> byteBuffers = new Stack<>();
    private Throwable throwable;
    private boolean closed = false;
    Disposable disposable;

    @Override
    public void onSubscribe(Disposable d) {
        disposable = d;
    }

    @Override
    public void onNext(byte[] bytes) {
        if (!closed) {
            accept(bytes);
        }
    }

    @Override
    public void onError(Throwable e) {
        throwable = e;
    }

    @Override
    public void onComplete() {

    }

    public InputStreamObserver() {
        super();
    }

    private ByteBuffer getBuffer() {
        if (byteBuffers.size() == 0) {
            byteBuffers.push(ByteBuffer.allocate(BUF_CAPACITY));
        }
        return byteBuffers.peek();
    }

    private void accept(byte[] val) {
        synchronized (byteBuffers) {
            int bufcount = val.length / BUF_CAPACITY;
            getBuffer().put(val, 0, Math.min(val.length, BUF_CAPACITY));
            for (int i = 0; i < bufcount; i++) {
                byteBuffers.push(ByteBuffer.allocate(BUF_CAPACITY));
                int offset = i * BUF_CAPACITY;
                int len = Math.min(val.length, BUF_CAPACITY);
                getBuffer().put(val, offset, len);
            }
        }
    }

    private int get(byte[] result, int offset, int len) throws IOException{
        if (throwable != null) {
            throw new IOException(throwable.getMessage());
        }

        if (closed) {
            throw new IOException("closed");
        }

        synchronized (byteBuffers) {
            try {
                int read = 0;
                int bufcount = len / BUF_CAPACITY;
                int l = Math.min(len, BUF_CAPACITY);
                getBuffer().get(result, offset, l);
                read += l;
                for (int i = 0; i < bufcount; i++) {
                    byteBuffers.pop();
                    int offs = i * BUF_CAPACITY;
                    l = Math.min(len, BUF_CAPACITY);
                    getBuffer().get(result, offset + offs, l);
                    read += l;
                }
                return read;
            } catch (BufferUnderflowException ignored) {
                return 0;
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
        synchronized (byteBuffers) {
            if (n >= Integer.MAX_VALUE || n <= Integer.MIN_VALUE) {
                throw new IOException("index out of range");
            }
            long skip = 0;
            int index = (int) n;
            long bufcount = n / BUF_CAPACITY;
            for (int i = 0; i < bufcount; i++) {
                byteBuffers.pop();
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
        return getBuffer().remaining() + BUF_CAPACITY * (byteBuffers.size()-1);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        synchronized (disposable) {
            if (disposable != null) {
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
        synchronized (byteBuffers) {
            if (getBuffer().remaining() > 0) {
                return getBuffer().get();
            } else if (byteBuffers.size() > 1) {
                byteBuffers.pop();
                return getBuffer().get();
            } else {
                return -1;
            }
        }
    }
}
