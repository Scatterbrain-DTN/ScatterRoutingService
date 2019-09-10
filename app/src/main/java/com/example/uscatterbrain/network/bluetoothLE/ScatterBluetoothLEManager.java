package com.example.uscatterbrain.network.bluetoothLE;

import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import com.example.uscatterbrain.DeviceProfile;
import com.example.uscatterbrain.ScatterRoutingService;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.Delayed;

public class ScatterBluetoothLEManager {
    public static final String TAG = "BluetoothLE";
    public static final int SERVICE_ID = 0xFEEF;
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_READ_SSID = UUID.fromString("9a22e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_READ_PSK =  UUID.fromString("9a23e79f-4a6d-4e28-95c6-257f5e47fd90");
    private Context mService;

    private final int REQUEST_ENABLE_BT = 1;

    private BluetoothLeAdvertiser mAdvertiser;
    private BluetoothLeScanner mScanner;
    private BluetoothAdapter mAdapter;
    private AdvertisingSet current;
    private BluetoothGatt mGatt;
    private BluetoothGattServer mGattServer;
    private ScanCallback leScanCallback;

    private final Handler mHandler = new Handler();

    private Map<String,BluetoothDevice> deviceList;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private int connectionState = STATE_DISCONNECTED;


    private static final int STATE_DISCOVER = 0;
    private static final int STATE_CONNECT = 1;
    private static final int STATE_IDLE = 3;
    private int currentstate = STATE_IDLE;

    public static long DEFAULT_SCAN_TIME = 30 * 1000;
    public static long DEFAULT_CONNECT_TIME = 190 * 1000;
    public static long DEFAULT_COOLDOWN_TIMEOUT = 1 * 1000;

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);


            if(characteristic.getUuid().equals(UUID_READ_SSID)) {
                AdvertisePacket ap = new AdvertisePacket(((ScatterRoutingService) mService).getProfile());
                mGattServer.sendResponse(device,  requestId,BluetoothGatt.GATT_SUCCESS, 0, ap.getBytes());
                Log.v(TAG, "Wrote AdvertisePacket");
            } else {
                Log.v(TAG, "Someone tried to read a nonexistant UUID " + characteristic.getUuid());
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionState = STATE_CONNECTED;
                    Log.i(TAG, "Connected to GATT server " + gatt.getDevice().getAddress() + "discovering services");

                    gatt.discoverServices();


            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server " + gatt.getDevice().getAddress());
                processOneScanResults();
            }

        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if(characteristic.getUuid().equals(SERVICE_UUID)) {
                    byte[] value = characteristic.getValue();
                    try {
                        AdvertisePacket ad = new AdvertisePacket(value);
                        //TODO: create transaction class
                        Log.v(TAG, "Successfully read AdvertisePacket");
                    } catch(InvalidProtocolBufferException e) {
                        Log.w(TAG, "err: received invalid advertisepacket from GATT");
                    }
                } else {
                    Log.w(TAG, "err: received GATT characteristic with wrong UUID");
                }
            }

        }


        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {


            BluetoothGattService s = gatt.getService(SERVICE_UUID);
            if(s != null) {
                BluetoothGattCharacteristic ch = s.getCharacteristic(UUID_READ_SSID);
                if(ch != null) {
                    gatt.readCharacteristic(ch);
                    Log.v(TAG, "starting characteristic read");
                } else {
                    Log.e(TAG, "read failed, characteristic is null");
                }
            } else {
                Log.e(TAG, "read failed, service is null");
            }

        }


    };

    private void multiPartMessage(BluetoothGattCharacteristic characteristic, byte[] data) {

    }

    public List<BluetoothGattService> getSupportedGattServices() {
        if (mGatt == null) return null;
        return mGatt.getServices();
    }

    public ScatterBluetoothLEManager(Context context) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdvertiser = mAdapter.getBluetoothLeAdvertiser();
        mScanner = mAdapter.getBluetoothLeScanner();


        deviceList = new HashMap<>();


        deviceList = new HashMap<>();

        this.mService = context;

        BluetoothManager btmanager = (BluetoothManager) mService.getSystemService(Context.BLUETOOTH_SERVICE);
        mGattServer = btmanager.openGattServer(mService, gattServerCallback);

        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                BluetoothDevice device = result.getDevice();

                if(result.getScanRecord()== null) {
                    Log.e(TAG, "onScanResult called with null service record");
                    return;
                }

                for(ParcelUuid p : result.getScanRecord().getServiceUuids()) {
                    if (p.getUuid().equals(SERVICE_UUID)) {
                        Log.v(TAG, "found a scatterbrain UUID");
                        deviceList.put(device.getAddress(),device);
                        break;
                    } else {
                        Log.v(TAG, "found nonscatterbrain UUID " + p.getUuid().toString());
                    }

                }

                Log.v(TAG, "enqueued scan result " + device.getAddress());
            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                Log.v(TAG, "scan failed " + errorCode);
            }
        };
    }



    private boolean processOneScanResults() {
        if(currentstate != STATE_CONNECT)
            return false;
        try {
            String key = deviceList.entrySet().iterator().next().getKey();
            BluetoothDevice d = deviceList.remove(key);
            mGatt = d.connectGatt(mService, false, gattCallback);
        } catch(NoSuchElementException e) {
            return false;
        }
        return true;
    }

    public void processPeers(long timeoutmillis) {
        currentstate = STATE_CONNECT;
        processOneScanResults();

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(mGatt != null) {
                    mGatt.disconnect();
                }
                currentstate = STATE_IDLE;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onConnectTimeout();
                    }
                }, DEFAULT_COOLDOWN_TIMEOUT);

            }
        }, timeoutmillis);
    }


    private void onDiscoverTimeout() {
        Log.v(TAG, "Discovery timed out, processing peers");
        processPeers(DEFAULT_CONNECT_TIME);
    }

    private void onConnectTimeout() {
        Log.v(TAG, "Connedt timed out, switching back to discovery");
        scanTime(DEFAULT_SCAN_TIME);
    }

    public void scanTime(long scantimemillis) {
        Log.v(TAG, "Starting LE scan");
        deviceList.clear();
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

                currentstate = STATE_DISCOVER;
                mScanner.startScan(sflist, settings, leScanCallback);

            }
        });

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mScanner.stopScan(leScanCallback);
                currentstate = STATE_IDLE;

                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onDiscoverTimeout();
                    }
                },DEFAULT_COOLDOWN_TIMEOUT);

            }
        }, scantimemillis);

    }

    public void startScan() {
        scanTime(DEFAULT_SCAN_TIME);
    }

    public void stopScan() {
        Log.v(TAG, "Stopping LE scan");
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                 mScanner.stopScan(leScanCallback);
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

        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic ssidCharacteristic = new
                BluetoothGattCharacteristic(UUID_READ_SSID, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        service.addCharacteristic(ssidCharacteristic);
        mGattServer.addService(service);

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
