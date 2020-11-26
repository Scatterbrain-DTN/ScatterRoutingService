package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;

public class TransactionResult {
    public final String nextStage;
    public final BluetoothDevice device;
    public TransactionResult(String nextStage, BluetoothDevice device) {
        this.nextStage = nextStage;
        this.device = device;
    }
}
