package com.example.uscatterbrain.network;

import org.reactivestreams.Subscription;

import io.reactivex.FlowableSubscriber;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;

public class InputStreamFlowableSubscriber extends InputStreamCallback
        implements FlowableSubscriber<byte[]> {
    boolean isDisposed = false;
    @Override
    public void onSubscribe(@NonNull Subscription s) {
        Disposable d = new Disposable() {
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
