package com.example.uscatterbrain.network.bluetoothLE;

import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.example.uscatterbrain.ScatterRoutingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ScatterBluetoothLEManager {
    public static final String TAG = "BluetoothLE";
    public static final int SERVICE_ID = 0xFEEF;
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");

    private final int REQUEST_ENABLE_BT = 1;

    private BluetoothLeAdvertiser mAdvertiser;
    private BluetoothLeScanner mScanner;
    private BluetoothAdapter mAdapter;
    private AdvertisingSet current;

    private ScanCallback leScanCallback;

    private Map<String, BluetoothDevice> deviceList;

    public ScatterBluetoothLEManager(Service mService) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdvertiser = mAdapter.getBluetoothLeAdvertiser();
        mScanner = mAdapter.getBluetoothLeScanner();
        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                BluetoothDevice device = result.getDevice();
                deviceList.put(device.getAddress(), device);
                Log.v(TAG, "enqueued scan result " + device.getAddress());
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                super.onBatchScanResults(results);
                for(ScanResult result : results){
                    deviceList.put(result.getDevice().getAddress(), result.getDevice());
                    Log.v(TAG, "batchEnqueued " + results.size() + " scan results");
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
            }
        };
    }

    private void processScanResults() {
        for(Map.Entry<String, BluetoothDevice> entry: deviceList.entrySet()) {
            //TODO: initiate GATT, perform transfers, etc
        }

        deviceList.clear();
    }


    public void startScan() {
        Log.v(TAG, "Starting LE scan");
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                ScanFilter s = new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                        .build();

                ScanSettings settings = new ScanSettings.Builder()
                        .build();

                List<ScanFilter> sflist = new ArrayList<>();
                sflist.add(s);


                mScanner.startScan(sflist, settings, leScanCallback);
            }
        });
    }

    public void stopScan() {
        Log.v(TAG, "Stopping LE scan");
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                mScanner.stopScan(leScanCallback);
                processScanResults();
            }
        });
    }

    public void enableBluetooth(Activity activity) {
        if (mAdapter == null || !mAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);

            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

    }

    public void startLEAdvertise(byte[] data) {
        Log.v(TAG, "Starting LE advertise");

        if(data.length > 20) {
            Log.e(TAG, "err: data is longer than LE advertise frame");
            return;
        }


        if(Build.VERSION.SDK_INT >= 26) {
            AdvertisingSetParameters parameters = (new AdvertisingSetParameters.Builder())
                    .setLegacyMode(true) // True by default, but set here as a reminder.
                    .setConnectable(true)
                    .setScannable(true)
                    .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                    .build();

            AdvertiseData addata = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceData(new ParcelUuid(SERVICE_UUID), data)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            AdvertisingSetCallback callback = new AdvertisingSetCallback() {
                @Override
                public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                    super.onAdvertisingSetStarted(advertisingSet, txPower, status);
                    current = advertisingSet;
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

    public boolean setAdvertisingData(byte[] data) {
        Log.v(TAG, "setting advertise data");
        if(current == null) {
            Log.e(TAG, "err: AdvertisingSet is null");
            return false;
        }

        if(data.length > 20) {
            Log.e(TAG, "err: data is longer than LE advertise frame");
            return false;
        }

        if(Build.VERSION.SDK_INT >= 26) {
            current.setAdvertisingData(new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceData(new ParcelUuid(SERVICE_UUID), data)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build());
        } else {
            Log.e(TAG, "err: could not update advertising data due to wrong SDK version");
        }

        return true;
    }
}
