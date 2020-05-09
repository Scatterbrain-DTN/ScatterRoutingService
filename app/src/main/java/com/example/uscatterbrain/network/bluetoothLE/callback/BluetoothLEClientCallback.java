package com.example.uscatterbrain.network.bluetoothLE.callback;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModule;

import java.io.ByteArrayInputStream;

import no.nordicsemi.android.ble.callback.profile.ProfileDataCallback;
import no.nordicsemi.android.ble.data.Data;

public abstract class BluetoothLEClientCallback implements ProfileDataCallback {
    @Override
    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
        Log.v(BluetoothLERadioModule.TAG, "received BLE data len " + data.size() +
                " from " + device.getAddress());
        ByteArrayInputStream is = new ByteArrayInputStream(data.getValue());
        AdvertisePacket packet = AdvertisePacket.parseFrom(is);
        onReceivedAdvertise(packet);
    }

    @Override
    public void onInvalidDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
        Log.e(BluetoothLERadioModule.TAG, "invalid BLE data received from " + device.getAddress());
        onReceivedAdvertise(null);
    }

    public abstract void onReceivedAdvertise(AdvertisePacket packet);
}
