package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.callback.BluetoothLEClientCallback;

import no.nordicsemi.android.ble.BleManager;

import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;

public class BluetoothLEManager<T extends BluetoothLEClientCallback> extends BleManager {
    public static final String TAG = "BluetoothManager";
    private Context mContext;
    private BluetoothGattCharacteristic mAdvertiseReadCharacteristc;
    private BluetoothGattCharacteristic mAdvertiseWriteCharacteristic;
    private BluetoothGattCharacteristic mServerAdvertiseWriteCharacteristic;
    private BluetoothGattCharacteristic mServerAdvertiseReadCharacteristic;
    private T mDataCallback;

    public BluetoothLEManager(Context ctx) {
        super(ctx);
    }

    public T getCallback() {
        return mDataCallback;
    }

    public void setCallback(@NonNull T callback) {
        mDataCallback = callback;
    }

    /* BleManager overrides */

    @NonNull
    @Override
    protected BleManagerGattCallback getGattCallback() {
        return new MyManagerGattCallback();
    }

    /**
     * BluetoothGatt callbacks object.
     */
    private class MyManagerGattCallback extends BleManagerGattCallback {

        @Override
        protected void onServerReady(@NonNull final BluetoothGattServer server) {
            // Obtain your server attributes.
            mServerAdvertiseReadCharacteristic = server
                    .getService(BluetoothLERadioModule.SERVICE_UUID)
                    .getCharacteristic(BluetoothLERadioModule.UUID_READ_ADVERTISE);
            mServerAdvertiseWriteCharacteristic = server
                    .getService(BluetoothLERadioModule.SERVICE_UUID)
                    .getCharacteristic(BluetoothLERadioModule.UUID_WRITE_ADVERTISE);
        }

        @Override
        protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
            final BluetoothGattService service = gatt.getService(BluetoothLERadioModule.SERVICE_UUID);

            if (service != null) {
                mAdvertiseReadCharacteristc = service.getCharacteristic(BluetoothLERadioModule.UUID_READ_ADVERTISE);
                mAdvertiseWriteCharacteristic = service.getCharacteristic(BluetoothLERadioModule.UUID_WRITE_ADVERTISE);
            }
            if (mAdvertiseReadCharacteristc != null) {
                final int properties = mAdvertiseReadCharacteristc.getProperties();
                if ((properties & PROPERTY_NOTIFY) == 0) {
                    return false;
                }
            }

            if (mAdvertiseWriteCharacteristic != null) {
                final int properties = mAdvertiseWriteCharacteristic.getProperties();
                if ((properties & PROPERTY_NOTIFY) == 0) {
                    return false;
                }
            }

            return mAdvertiseReadCharacteristc != null && mAdvertiseWriteCharacteristic != null;
        }

        @Override
        protected void onDeviceDisconnected() {
            // [...]
            mAdvertiseReadCharacteristc = null;
            mAdvertiseWriteCharacteristic = null;
            mServerAdvertiseWriteCharacteristic = null;
            mServerAdvertiseReadCharacteristic = null;
        }

        @Override
        protected void initialize() {
            super.initialize();
        }
    }

    public void initiateTransaction(AdvertisePacket packet) {
        waitForNotification(mAdvertiseReadCharacteristc)
                .trigger(
                        writeCharacteristic(mAdvertiseWriteCharacteristic, packet.getBytes())
                        .done(device -> Log.v(BluetoothLERadioModule.TAG, "sent advertisepacket"))
                )
                .with(mDataCallback)
                .enqueue();
    }


}
