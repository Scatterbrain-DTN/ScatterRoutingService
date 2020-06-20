package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.ScatterCallback;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.callback.BluetoothLEClientCallback;

import java.io.Closeable;

import no.nordicsemi.android.ble.PhyRequest;
import no.nordicsemi.android.ble.observer.BondingObserver;
import no.nordicsemi.android.ble.observer.ConnectionObserver;

public class BluetoothLEClientObserver implements ConnectionObserver, Closeable {
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

    @Override
    public void close() {
        mManager.close();
    }

    public void connect(@NonNull final BluetoothDevice device, ScatterCallback<Boolean, Void> callback) {
        mManager = new BluetoothLEManager<>(mContext);
        mManager.setConnectionObserver(this);
        mManager.connect(device)
                .timeout(1000000)
                .retry(3,300)
                .useAutoConnect(false)
                .fail((a, b) -> {
                    Log.e(BluetoothLERadioModule.TAG, "failed to connect to client: " + b);
                    callback.call(false);
                })
                .done(log -> {
                    Log.v(BluetoothLERadioModule.TAG,
                            "connected to device " + device.getAddress() + " "
                    + log.toString());
                    initiateTransaction(callback);
                })
                .enqueue();
    }

    private void initiateTransaction(ScatterCallback<Boolean, Void> callback) {
        if (mManager.isConnected()) {
            mManager.initiateTransaction(mPacket, callback);
        } else {
            callback.call(false);
        }
    }

    public void setPacket(@NonNull AdvertisePacket packet) {
        mPacket = packet;
    }

    public  AdvertisePacket getPacket() {
        return mPacket;
    }
}
