package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.example.uscatterbrain.ScatterRoutingService;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.ScatterPeerHandler;
import com.example.uscatterbrain.network.bluetoothLE.callback.BluetoothLEClientCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;

public class BluetoothLERadioModule implements ScatterPeerHandler {
    public static final String TAG = "BluetoothLE";
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_READ_ADVERTISE = UUID.fromString("9a22e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_WRITE_ADVERTISE = UUID.fromString("9a23e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_READ_UPGRADE =  UUID.fromString("9a24e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_WRITE_UPGRADE = UUID.fromString("9a25e79f-4a6d-4e28-95c6-257f5e47fd90");
    private AdvertisePacket mAdvertise;
    private Context mContext;
    private UUID mModuleUUID;
    private List<UUID> mPeers;
    private AdvertisingSet mAdvertisingSet;
    private BluetoothLeAdvertiser mAdvertiser;
    private BluetoothLEClientObserver mClientObserver;
    private BluetoothLEServerObserver mServerObserver;


    public BluetoothLERadioModule() {
        mContext = null;
        mPeers = new ArrayList<>();
        mAdvertise = null;
    }

    //TODO: implement
    @Override
    public void setAdvertisePacket(AdvertisePacket advertisePacket) {
        mAdvertise = advertisePacket;
    }

    @Override
    public void setOnPeersChanged(PeersChangedCallback callback) {

    }

    @Override
    public AdvertisePacket getAdvertisePacket() {
        return mAdvertise;
    }

    @Override
    public void startAdvertise() throws AdvertiseFailedException {

    }

    @Override
    public void stopAdvertise() throws AdvertiseFailedException {

    }

    @Override
    public void startDiscover() throws AdvertiseFailedException {

    }

    @Override
    public void stopDiscover() throws AdvertiseFailedException {

    }

    @Override
    public UUID register(ScatterRoutingService service) {
        this.mContext = service;
        mModuleUUID = UUID.randomUUID();
        mAdvertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        mServerObserver = new BluetoothLEServerObserver(mContext);
        mClientObserver = new BluetoothLEClientObserver(mContext,service.getPacket());
        return mModuleUUID;
    }

    @Override
    public List<UUID> getPeers() {
        return mPeers;
    }

    @Override
    public UUID getModuleID() {
        return mModuleUUID;
    }

    @Override
    public boolean isRegistered() {
        return mModuleUUID != null;
    }


    public void startLEAdvertise(byte[] data) {
        Log.v(TAG, "Starting LE advertise");

        if(data.length > 20) {
            Log.e(TAG, "err: data is longer than LE advertise frame");
            return;
        }

        BluetoothGattService service = new BluetoothGattService(BluetoothLERadioModule.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic ssidCharacteristic = new
                BluetoothGattCharacteristic(BluetoothLERadioModule.UUID_READ_ADVERTISE, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        service.addCharacteristic(ssidCharacteristic);

        if(Build.VERSION.SDK_INT >= 26) {
            AdvertisingSetParameters parameters = (new AdvertisingSetParameters.Builder())
                    .setLegacyMode(true) // True by default, but set here as a reminder.
                    .setConnectable(true)
                    .setScannable(true)
                    .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build();

            AdvertiseData addata = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(new ParcelUuid(BluetoothLERadioModule.SERVICE_UUID))
                    .build();
            AdvertisingSetCallback callback = new AdvertisingSetCallback() {
                @Override
                public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                    super.onAdvertisingSetStarted(advertisingSet, txPower, status);
                    mAdvertisingSet = advertisingSet;
                }
            };

            mAdvertiser.startAdvertisingSet(parameters, addata, null,
                    null, null, callback);

        } else {
            Log.e(TAG, "err: could not start LE advertise due to wrong SDK version");
        }
    }

    public void stopLEAdvertise() {
        Log.v(TAG, "stopping LE advertise");
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

}
