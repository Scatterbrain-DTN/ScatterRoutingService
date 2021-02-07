package net.ballmerlabs.uscatterbrain.network;

import org.reactivestreams.Subscription;

import java.io.IOException;

import io.reactivex.FlowableSubscriber;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;

public class InputStreamFlowableSubscriber extends InputStreamCallback
        implements FlowableSubscriber<byte[]> {
    private boolean isDisposed = false;
    private Subscription subscription;
    private int blocksize;
    public static final int DEFAULT_BLOCKSIZE = 20;

    public InputStreamFlowableSubscriber() {
        this.blocksize = DEFAULT_BLOCKSIZE;
    }

    public InputStreamFlowableSubscriber(int blocksize) {
        this.blocksize = blocksize;
    }

    public void setBlocksize(int blocksize) {
        this.blocksize = blocksize;
    }

    @Override
    public void onSubscribe(@NonNull Subscription s) {
        s.request(blocksize*20);
        subscription = s;
        disposable = new Disposable() {
            @Override
            public void dispose() {
                isDisposed = true;
                s.cancel();
            }

            @Override
            public boolean isDisposed() {
                return isDisposed;
            }
        };
    }

    @Override
    public void onNext(byte[] bytes) {
        if (!closed) {
            acceptBytes(bytes);
        }
    }

    @Override
    public void onError(Throwable e) {
        throwable = e;
        try {
            close();
        } catch (IOException ignored) {

        }
    }

    @Override
    public void onComplete() {

    }

    @Override
    public int read(byte[] b) throws IOException {
        if (subscription != null) {
            subscription.request(Math.max(blocksize, b.length));
        }
        return super.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (subscription != null) {
            subscription.request(Math.max(blocksize, Math.min(b.length, len)));
        }
        return super.read(b, off, len);
    }

    @Override
    public int read() throws IOException {
        if (subscription != null) {
            subscription.request(1);
        }
        return super.read();
    }


    @Override
    public long skip(long n) throws IOException {
        if (subscription != null) {
            subscription.request(Math.max(blocksize, n));
        }
        return super.skip(n);
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
