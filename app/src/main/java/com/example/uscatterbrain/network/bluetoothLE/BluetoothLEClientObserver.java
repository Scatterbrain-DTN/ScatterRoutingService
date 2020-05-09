package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.callback.BluetoothLEClientCallback;

import no.nordicsemi.android.ble.observer.BondingObserver;
import no.nordicsemi.android.ble.observer.ConnectionObserver;

public class BluetoothLEClientObserver implements ConnectionObserver {
    private BluetoothLEManager<BluetoothLEClientCallback> mManager;
    private Context mContext;
    private AdvertisePacket mPacket;

    public BluetoothLEClientObserver(@NonNull Context context,
                                     @NonNull AdvertisePacket packet) {
        mPacket = packet;
        mContext = context;
    }

    /* implementation for ConnectionObserver */
    @Override
    public void onDeviceConnecting(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "onDeviceConnecting");
    }

    @Override
    public void onDeviceConnected(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "onDeviceConnected");
    }

    @Override
    public void onDeviceFailedToConnect(@NonNull BluetoothDevice device, int reason) {
        Log.v(BluetoothLERadioModule.TAG, "onDeviceFailedtoConnect");
    }

    @Override
    public void onDeviceReady(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "onDeviceReady");
    }

    @Override
    public void onDeviceDisconnecting(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "onDeviceDisconnecting");
    }

    @Override
    public void onDeviceDisconnected(@NonNull BluetoothDevice device, int reason) {
        Log.v(BluetoothLERadioModule.TAG, "onDeviceDisconnected");
    }

    public void connect(@NonNull final BluetoothDevice device) {
        mManager = new BluetoothLEManager<>(mContext);
        mManager.setConnectionObserver(this);
        mManager.setCallback(new BluetoothLEClientCallback() {
            @Override
            public void onReceivedAdvertise(AdvertisePacket packet) {
                //TODO:2
            }
        });
        mManager.connect(device)
                .timeout(100000)
                .retry(3,100)
                .done(log -> Log.v(BluetoothLERadioModule.TAG,
                        "connected to device " + device.getAddress()))
                .enqueue();
    }

    private void initiateTransaction() {
        if (mManager.isConnected()) {
            mManager.initiateTransaction(mPacket);
        }
    }

    public void setPacket(@NonNull AdvertisePacket packet) {
        mPacket = packet;
    }

    public  AdvertisePacket getPacket() {
        return mPacket;
    }
}
