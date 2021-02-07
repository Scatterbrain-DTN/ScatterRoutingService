package net.ballmerlabs.uscatterbrain.network;

import java.io.IOException;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class InputStreamObserver extends InputStreamCallback implements Observer<byte[]> {

    public InputStreamObserver(int capacity) {
        super(capacity);
    }

    public InputStreamObserver() {
        super();
    }

    @Override
    public void onSubscribe(Disposable d) {
        disposable = d;
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
}
