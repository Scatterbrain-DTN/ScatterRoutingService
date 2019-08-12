package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.os.ParcelUuid;

import java.util.UUID;

public class ScatterBluetoothLEManager {
    public static final String TAG = "BluetoothLE";
    public static final int SERVICE_ID = 0xFEEF;
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");

    BluetoothLeAdvertiser mAdvertiser;
    BluetoothLeScanner mScanner;
    BluetoothAdapter mAdapter;
    AdvertisingSet current;

    public ScatterBluetoothLEManager() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdvertiser = mAdapter.getBluetoothLeAdvertiser();
        mScanner = mAdapter.getBluetoothLeScanner();
    }

    public void startLEAdvertise() {
        AdvertisingSetParameters parameters = (new AdvertisingSetParameters.Builder())
                .setLegacyMode(true) // True by default, but set here as a reminder.
                .setConnectable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = (new AdvertiseData.Builder()).setIncludeDeviceName(true).build();

        AdvertisingSetCallback callback = new AdvertisingSetCallback() {
            @Override
            public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                super.onAdvertisingSetStarted(advertisingSet, txPower, status);
                current = advertisingSet;
            }
        };

        mAdvertiser.startAdvertisingSet(parameters, data, null,
                null, null, callback);
    }

    public void stopLEAdvertise() {
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
            }
        };

        mAdvertiser.stopAdvertising(callback);
    }

    public boolean setAdvertisingData(byte[] data) {
        if(current == null)
            return false;

        current.setScanResponseData(new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .addServiceData(new ParcelUuid(SERVICE_UUID), data)
                .build());

        return true;
    }
}
