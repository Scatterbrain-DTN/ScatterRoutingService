package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.ScatterCallback;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.callback.BluetoothLEClientCallback;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.PhyRequest;

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

    @Override
    public void log(int priority, @NonNull String message) {
        Log.println(priority, BluetoothLERadioModule.TAG, message);
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
            super.onServerReady(server);
            Log.v(BluetoothLERadioModule.TAG, "client onServerReady");
            // Obtain your server attributes.
            mServerAdvertiseReadCharacteristic = server
                    .getService(BluetoothLERadioModule.SERVICE_UUID)
                    .getCharacteristic(BluetoothLERadioModule.UUID_READ_ADVERTISE);
            mServerAdvertiseWriteCharacteristic = server
                    .getService(BluetoothLERadioModule.SERVICE_UUID)
                    .getCharacteristic(BluetoothLERadioModule.UUID_WRITE_ADVERTISE);

            setWriteCallback(mServerAdvertiseWriteCharacteristic)
                    .with((device, data) ->
                            Log.v(BluetoothLERadioModule.TAG, "received " + data));
        }


        @Override
        protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
            Log.v(BluetoothLERadioModule.TAG, "isRequiredServiceSupported");
            final BluetoothGattService service = gatt.getService(BluetoothLERadioModule.SERVICE_UUID);

            if (service != null) {
                Log.e(BluetoothLERadioModule.TAG, "err: service is null");
                mAdvertiseReadCharacteristc = service.getCharacteristic(BluetoothLERadioModule.UUID_READ_ADVERTISE);
                mAdvertiseWriteCharacteristic = service.getCharacteristic(BluetoothLERadioModule.UUID_WRITE_ADVERTISE);
            }
            if (mAdvertiseReadCharacteristc != null) {
                final int properties = mAdvertiseReadCharacteristc.getProperties();
                if ((properties & PROPERTY_NOTIFY) == 0) {
                    Log.e(BluetoothLERadioModule.TAG, "read characteristic does not support notify");
                    return false;
                }
            }

            if (mAdvertiseWriteCharacteristic != null) {
                final int properties = mAdvertiseWriteCharacteristic.getProperties();
                if ((properties & PROPERTY_NOTIFY) == 0) {
                    Log.e(BluetoothLERadioModule.TAG, "read characteristic does not support notify");
                    return false;
                }
            }
            if (mAdvertiseWriteCharacteristic== null || mAdvertiseReadCharacteristc == null){
                Log.e(BluetoothLERadioModule.TAG, "error characteristics are null");
                return false;
            } else {
                return true;
            }
        }

        @Override
        protected void onDeviceDisconnected() {
            Log.v(BluetoothLERadioModule.TAG, "onDeviceDisconected");
            // [...]
            mAdvertiseReadCharacteristc = null;
            mAdvertiseWriteCharacteristic = null;
        }

        @Override
        protected void initialize() {
            super.initialize();
            Log.v(BluetoothLERadioModule.TAG, "client manager initialize()");
            // You may enqueue multiple operations. A queue ensures that all operations are
            // performed one after another, but it is not required.
            beginAtomicRequestQueue()
                    .add(setPreferredPhy(PhyRequest.PHY_LE_1M_MASK , PhyRequest.PHY_LE_1M_MASK, PhyRequest.PHY_OPTION_NO_PREFERRED)
                            .fail((device, status) -> log(Log.WARN, "Requested PHY not supported: " + status)))
                    .add(enableNotifications(mAdvertiseWriteCharacteristic))
                    .add(enableNotifications(mAdvertiseReadCharacteristc))
                    .add(enableNotifications(mServerAdvertiseReadCharacteristic))
                    .add(enableNotifications(mServerAdvertiseWriteCharacteristic))
                    .done(device -> log(Log.INFO, "Target initialized"))
                    .enqueue();
        }
    }

    public void initiateTransaction(AdvertisePacket packet, ScatterCallback<Boolean, Void> callback) {
        waitForNotification(mAdvertiseReadCharacteristc)
                .trigger(
                        writeCharacteristic(mAdvertiseWriteCharacteristic, packet.getBytes())
                                .split()
                                .fail((device,b) -> {
                                    Log.e(BluetoothLERadioModule.TAG,"failed write advertisepacket");
                                    callback.call(false);
                                })
                        .done(device -> {
                            Log.v(BluetoothLERadioModule.TAG, "sent advertisepacket");
                            callback.call(true);
                        })
                )
                .with(mDataCallback)
                .enqueue();
    }


}
