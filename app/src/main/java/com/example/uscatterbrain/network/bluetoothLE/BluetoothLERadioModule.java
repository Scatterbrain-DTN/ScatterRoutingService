package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.ScatterCallback;
import com.example.uscatterbrain.ScatterRoutingService;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.ScatterPeerHandler;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.ServerConfig;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private Map<BluetoothDevice, UUID> mPeers;
    private AdvertisingSet mAdvertisingSet;
    private AdvertiseCallback mAdvertiseCallback;
    private BluetoothLeAdvertiser mAdvertiser;
    private RxBleServer mServer;



    public BluetoothLERadioModule() {
        mContext = null;
        mPeers = new HashMap<>();
        mAdvertise = null;
        mCurrentResults = new HashMap<>();
    }

    private void processPeers(ScatterCallback<Void, Void> callback) {
        Log.v(TAG, "processing " + mCurrentResults.size() + " peers");
        if (mCurrentResults.size() == 0) {
            callback.call(null);
            return;
        }
        mProcessedPeerCount = 0;
        for (Map.Entry<String, ScanResult> result : mCurrentResults.entrySet()) {
            Log.v(TAG, "processing result " + result.getKey());
            mClientObserver.connect(result.getValue().getDevice(), success -> {
                if (success) {
                    Log.v(TAG, "successfully sent blockdata packet");
                } else {
                    Log.e(TAG, "failed to send blockdata packet");
                }
                mProcessedPeerCount++;
                if (mProcessedPeerCount >= mCurrentResults.size()) {
                    callback.call(null);
                }

                return null;
            });

        }
    }

    @Override
    public void setAdvertisePacket(AdvertisePacket advertisePacket) {
        mAdvertise = advertisePacket;
    }

    @Override
    public void setOnPeersChanged(ScatterCallback<List<UUID>, Void> callback) {
        mPeersChangedCallback = callback;
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
        mServer = RxBleServer.create(mContext);
        ServerConfig config = new ServerConfig();
        config.addService(new BluetoothGattService(UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY));
        Disposable flowDisplosable = mServer.openServer(config).subscribe(conection -> {
            Log.v(TAG, "device connected " + conection.getDevice().getAddress());
        });
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

        Log.v(TAG, "Starting LE advertise");
        if(Build.VERSION.SDK_INT >= 26) {

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();

            AdvertiseData addata = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(new ParcelUuid(BluetoothLERadioModule.SERVICE_UUID))
                    .build();

            mAdvertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    super.onStartSuccess(settingsInEffect);
                    Log.v(TAG, "successfully started advertise");

                    final BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
                    mServerObserver.startServer();
                }

                @Override
                public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                    super.onAdvertisingSetStarted(advertisingSet, txPower, status);
                    mAdvertisingSet = advertisingSet;
                }
            };

            mAdvertiser.startAdvertising(settings, addata, mAdvertiseCallback);

        } else {
            throw new AdvertiseFailedException("wrong sdk version");
        }
    }

    @Override
    public void stopAdvertise() throws AdvertiseFailedException {
        Log.v(TAG, "stopping LE advertise");

        if (mAdvertiseCallback == null) {
            throw new AdvertiseFailedException("already stopped");
        }

        mAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    @Override
    public void startDiscover(discoveryOptions opts) {
        if (mScanCallback != null) {
            return;
        }

        if (opts != discoveryOptions.OPT_DISCOVER_ONCE && opts != discoveryOptions.OPT_DISCOVER_FOREVER) {
            return; //TODO: handle this
        }

        mCurrentResults.clear();

        mScanCallback = new ScanCallback() {

            @Override
            public void onBatchScanResults(@NonNull List<ScanResult> results) {
                super.onBatchScanResults(results);

                for( ScanResult result : results) {
                    Log.v(TAG, "scan " + result.getDevice().getAddress());
                    mCurrentResults.put(result.getDevice().getAddress(), result);
                }

                stopDiscover();
                processPeers(key -> {
                    Log.v(TAG, "finished processing peers");
                    List<UUID> list = new ArrayList<>(); //TODO: remove placeholder
                    for (int x=0;x<mProcessedPeerCount;x++) {
                        list.add(UUID.randomUUID());
                    }
                    mPeersChangedCallback.call(list);
                    if (opts == discoveryOptions.OPT_DISCOVER_FOREVER) {
                        startDiscover(opts);
                    }
                    return null;
                });

            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                Log.e(TAG, "scan failed: " + errorCode);
                mPeersChangedCallback.call(null);
            }
        };

        BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        ScanSettings settings = new ScanSettings.Builder()
                .setLegacy(true)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(1000) //TODO: make configurable
                .setUseHardwareBatchingIfSupported(true)
                .setUseHardwareFilteringIfSupported(true)
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());
        scanner.startScan(filters, settings, mScanCallback);
    }

    @Override
    public void stopDiscover(){
        if (mScanCallback == null) {
            return;
        }
        BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        scanner.stopScan(mScanCallback);
    }

    @Override
    public UUID register(ScatterRoutingService service) {
        Log.v(BluetoothLERadioModule.TAG, "registered bluetooth LE radio module");
        this.mContext = service;
        mClientObserver = new BluetoothLEClientObserver(mContext, mAdvertise);

        mModuleUUID = UUID.randomUUID();
        mAdvertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        mServerObserver = new BluetoothLEServerObserver(mContext);
        try {
            startAdvertise();
        } catch (AdvertiseFailedException e) {
            Log.e(TAG, "failed to advertise");
        }
        return mModuleUUID;
    }

    @Override
    public List<UUID> getPeers() {
        return new ArrayList<UUID>(mPeers.values());
    }

    @Override
    public UUID getModuleID() {
        return mModuleUUID;
    }

    @Override
    public boolean isRegistered() {
        return mModuleUUID != null;
    }


}
