package com.example.uscatterbrain.network;

import java.util.List;
import java.util.UUID;

public interface ScatterTransferHandler extends ScatterRadioModule {
    void setOnReceiveCallback(OnReceiveCallback callback);
    void transmit(UUID destinationPeer, BlockDataObservableSource data) throws TransferException;
    void transmit(UUID destinatinPeer, List<BlockDataObservableSource> data) throws TransferException;

    interface OnReceiveCallback {
        void onReceive(UUID peer, BlockDataObservableSource data);
        void onReceive(UUID peer, List<BlockDataObservableSource> data);
    }

    class TransferException extends  Exception {
        private String name;

        public TransferException(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}
