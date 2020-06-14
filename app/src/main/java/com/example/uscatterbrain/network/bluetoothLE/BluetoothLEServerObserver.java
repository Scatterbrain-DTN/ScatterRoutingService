package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.network.bluetoothLE.callback.BluetoothLEClientCallback;

import no.nordicsemi.android.ble.observer.BondingObserver;
import no.nordicsemi.android.ble.observer.ConnectionObserver;
import no.nordicsemi.android.ble.observer.ServerObserver;

public class BluetoothLEServerObserver implements ServerObserver, ConnectionObserver, BondingObserver {
    private BluetoothLEServerManager mManager;
    private BluetoothLEManager<BluetoothLEClientCallback> mClientManager;
    private Context mContext;

    public BluetoothLEServerObserver(@NonNull final Context context) {
        mContext = context;
        mManager = new BluetoothLEServerManager(context);
        mManager.setServerObserver(this);
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

    /* implementation for ServerObserver */

    @Override
    public void onServerReady() {
        Log.v(BluetoothLERadioModule.TAG, "Bluetooth GATT server onServerReady");
    }

    @Override
    public void onDeviceConnectedToServer(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "Bluetooth GATT server onDeviceConnectedToServer: " +
                device.getAddress());
    }

    @Override
    public void onDeviceDisconnectedFromServer(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "Bluetooth GATT server onDeviceDisconnectedFromServer " +
                device.getAddress());
    }

    /* implementation for BondingObserver */

    @Override
    public void onBondingRequired(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "onBondingRequired: " + device.getAddress());
    }

    @Override
    public void onBonded(@NonNull BluetoothDevice device) {
        Log.v(BluetoothLERadioModule.TAG, "onBonded: " + device.getAddress());
    }

    @Override
    public void onBondingFailed(@NonNull BluetoothDevice device) {
        Log.e(BluetoothLERadioModule.TAG, "onBondingFailed " + device.getAddress());
    }

    public boolean startServer() {
        return mManager.open();
    }

    public void stopServer() {
        mManager.close();
    }
}