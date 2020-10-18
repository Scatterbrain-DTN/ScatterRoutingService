package com.example.uscatterbrain.network;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;

public class InputStreamObserver extends InputStreamCallback implements Observer<byte[]> {

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
    }

    @Override
    public void onComplete() {

    }
}
