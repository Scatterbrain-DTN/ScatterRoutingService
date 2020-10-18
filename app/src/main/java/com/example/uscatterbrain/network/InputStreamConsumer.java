package com.example.uscatterbrain.network;

import io.reactivex.functions.Consumer;

public class InputStreamConsumer extends InputStreamCallback implements Consumer<byte[]> {
    @Override
    public void accept(byte[] bytes) throws Exception {
        if (!closed) {
            acceptBytes(bytes);
        }
    }
}
