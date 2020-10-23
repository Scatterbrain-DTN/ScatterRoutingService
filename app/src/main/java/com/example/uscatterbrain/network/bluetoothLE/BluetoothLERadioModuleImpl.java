package com.example.uscatterbrain.network.bluetoothLE;

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
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BluetoothLEModuleInternal;
import com.example.uscatterbrain.network.InputStreamObserver;
import com.example.uscatterbrain.network.ScatterPeerHandler;
import com.example.uscatterbrain.network.ScatterRadioModule;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.RxBleServerConnection;
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
    public static final int CLIENT_CONNECT_TIMEOUT = 10;
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_ADVERTISE = UUID.fromString("9a22e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_UPGRADE =  UUID.fromString("9a24e79f-4a6d-4e28-95c6-257f5e47fd90");
    private final BluetoothGattService mService = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    public static final BluetoothGattCharacteristic ADVERTISE_CHARACTERISTIC = new BluetoothGattCharacteristic(
            UUID_ADVERTISE,
            BluetoothGattCharacteristic.PROPERTY_READ |
                    BluetoothGattCharacteristic.PROPERTY_WRITE |
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE |
                    BluetoothGattCharacteristic.PERMISSION_READ
    );

    public static final BluetoothGattCharacteristic UPGRADE_CHARACTERISTIC = new BluetoothGattCharacteristic(
            UUID_UPGRADE,
            BluetoothGattCharacteristic.PROPERTY_READ |
                    BluetoothGattCharacteristic.PROPERTY_WRITE |
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE |
                    BluetoothGattCharacteristic.PERMISSION_READ
    );
    private final CompositeDisposable mGattDisposable = new CompositeDisposable();
    private final Context mContext;
    private final Map<String, ServerPeerHandle> mServerPeers = new ConcurrentHashMap<>();
    private final Map<String,ClientPeerHandle> mClientPeers = new ConcurrentHashMap<>();
    private final Scheduler bleScheduler;
    private int discoverDelay = 45;
    private boolean discovering = true;
    private final AdvertiseCallback mAdvertiseCallback =  new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.v(TAG, "successfully started advertise");

            final BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.e(TAG, "failed to start advertise");
        }
    };;
    private BluetoothLeAdvertiser mAdvertiser;
    private RxBleServer mServer;
    private RxBleClient mClient;
    private AdvertisePacket mAdvertise;



    public BluetoothLERadioModule() {
        mContext = null;
        mPeers = new HashMap<>();
        mAdvertise = null;
        mCurrentResults = new HashMap<>();
    }

    private boolean isConnectedServer(BluetoothDevice device) {
        return mServerPeers.containsKey(device);
    }

    private boolean isConnectedClient(BluetoothDevice device) {
        return mClientPeers.containsKey(device);
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
    public Observable<UUID> getOnPeersChanged() {
        return null;
    }

    @Override
    public void setAdvertisePacket(AdvertisePacket advertisePacket) {
        mAdvertise = advertisePacket;
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
                    .addServiceUuid(new ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
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
    public void stopAdvertise() {
        Log.v(TAG, "stopping LE advertise");
        mAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    private Observable<RxBleConnection> getOrEstablishConnection(
            boolean autoconnect,
            Timeout timeout,
            ScanResult result
    ) {
        if (mClientPeers.containsKey(result.getBleDevice().getBluetoothDevice().getAddress())) {
            return Observable.just(
                    mClientPeers.get(result.getBleDevice().getBluetoothDevice().getAddress()).connection
            );
        }

        if (result.getBleDevice().getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTING ||
                result.getBleDevice().getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED) {
            return Observable.empty();
        }

        return result.getBleDevice().establishConnection(autoconnect, timeout)
                .map(connection -> {
                    Log.v(TAG, "LE connection successfully established.");
                    ClientPeerHandle peerHandle = new ClientPeerHandle(connection, mAdvertise);
                    mClientPeers.put(result.getBleDevice().getBluetoothDevice().getAddress(), peerHandle);
                    return connection;
                });
    }

    @Override
    public Observable<RxBleConnection> discoverOnce() {
        Log.d(TAG, "discover once called");
        return mClient.scanBleDevices(
                new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setShouldCheckLocationServicesState(true)
                        .build(),
                new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                        .build())
                .flatMap(scanResult -> {
                    Log.d(TAG, "scan result: " + scanResult.getBleDevice().getMacAddress());
                    if (isConnectedServer(scanResult.getBleDevice().getBluetoothDevice())) {
                        Log.e(TAG, "device " + scanResult.getBleDevice().getMacAddress() + " already connected to server");
                        return Observable.empty();
                    }
                    return getOrEstablishConnection(
                            true,
                            new Timeout(CLIENT_CONNECT_TIMEOUT, TimeUnit.SECONDS),
                            scanResult
                    );
                });
    }

    @Override
    public void startDiscover(discoveryOptions opts) {
        if (mScanCallback != null) {
            return;
        }
        Disposable d;

        if (opts == discoveryOptions.OPT_DISCOVER_ONCE) {
            d = discoverOnce()
                    .map(connection -> new ClientPeerHandle(connection, mAdvertise))
                    .flatMapSingle(ClientPeerHandle::handshake)
                    .subscribeOn(bleScheduler)
                    .subscribe(
                            packet -> Log.v(TAG, "handshake completed"),
                            err -> Log.e(TAG, "handshake failed: " + err)
                    );
            mGattDisposable.add(d);
        } else if (opts == discoveryOptions.OPT_DISCOVER_FOREVER) {
            d = discoverOnce()
                    .repeatWhen(func -> func.delay(discoverDelay, TimeUnit.SECONDS).skipWhile(p -> !discovering))
                    .map(connection -> new ClientPeerHandle(connection, mAdvertise))
                    .flatMapSingle(ClientPeerHandle::handshake)
                    .subscribeOn(bleScheduler)
                    .subscribe(
                            packet -> Log.v(TAG, "repeat handshake completed"),
                            err -> Log.e(TAG, "repeat handshake failed: " + err)
                    );
            mGattDisposable.add(d);
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
    public boolean startServer() {
        if (mServer == null) {
            return false;
        }

        ServerConfig config = ServerConfig.newInstance(new Timeout(5, TimeUnit.SECONDS))
                .addService(mService);

        Disposable d = mServer.openServer(config)
                .subscribeOn(bleScheduler)
                .subscribe(
                        connection -> {
                            Log.d(TAG, "gatt server connection from " + connection.getDevice().getAddress());
                            // we shouldn't maintain duplicate connections
                            if (isConnectedClient(connection.getDevice())) {
                                Log.d(TAG, "gatt server dropping duplicat connection: " + connection.getDevice().getAddress());
                                connection.disconnect();
                                return;
                            }
                            ServerPeerHandle handle = new ServerPeerHandle(connection, mAdvertise);
                            Disposable disconnect = connection.observeDisconnect()
                                    .subscribe(dc -> mServerPeers.remove(connection.getDevice().getAddress()), error -> {
                                        mServerPeers.remove(connection.getDevice().getAddress());
                                        Log.e(TAG, "error when disconnecting device " + connection.getDevice());
                                    });
                            mGattDisposable.add(disconnect);
                            mServerPeers.put(connection.getDevice().getAddress(), handle);
                        },
                        error -> {
                            Log.e(TAG, "error starting server " + error.getMessage());
                        }
                );

        mGattDisposable.add(d);
        return true;
    }

    public void stopServer() {
        mGattDisposable.dispose();
        mServer.closeServer();
    }

    @Override
    public UUID register(ScatterRoutingService service) {
        Log.v(BluetoothLERadioModuleImpl.TAG, "registered bluetooth LE radio module");
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

        return true;
    }

    public void stopServer() {
        mGattDisposable.dispose();
        mServer.closeServer();
    }

    @Override
    public List<UUID> getPeers() {
        return null;
    }

    private interface PeerHandle extends Closeable {
        Single<AdvertisePacket> handshake();

        @Override
        void close();
    }

    private static class ServerPeerHandle implements PeerHandle{
        private final RxBleServerConnection connection;
        private final CompositeDisposable peerHandleDisposable = new CompositeDisposable();
        private final AdvertisePacket advertisePacket;
        public ServerPeerHandle(
                RxBleServerConnection connection,
                AdvertisePacket advertisePacket
        ) {
            this.connection = connection;
            this.advertisePacket = advertisePacket;
        }

        public RxBleServerConnection getConnection() {
            return connection;
        }

        public Completable notifyAdvertise() {
            return connection.setupNotifications(
                    ADVERTISE_CHARACTERISTIC,
                    Observable.fromArray(advertisePacket.getBytes())
            );
        }

        public Single<AdvertisePacket> handshake() {
            Log.d(TAG, "called handshake");
            return notifyAdvertise()
                .andThen(Single.just(
                    connection.getOnCharacteristicWriteRequest(ADVERTISE_CHARACTERISTIC)
                    .map(ServerResponseTransaction::getValue)
                )
                    .flatMap(object -> {
                        Log.d(TAG, "handshake onCharacteristicWrite");
                        InputStreamObserver inputStreamObserver = new InputStreamObserver();
                        object.subscribe(inputStreamObserver);
                        return AdvertisePacket.parseFrom(inputStreamObserver);
                    }));
        }

        public void close() {
            peerHandleDisposable.dispose();
        }
    }

    private static class ClientPeerHandle {
        private final RxBleConnection connection;
        private final AdvertisePacket advertisePacket;
        private final CompositeDisposable disposable = new CompositeDisposable();
        public ClientPeerHandle(
                RxBleConnection connection,
                AdvertisePacket advertisePacket
        ) {
            this.connection = connection;
            this.advertisePacket = advertisePacket;
        }

        public Single<AdvertisePacket> handshake() {
            return connection.setupNotification(UUID_ADVERTISE)
                    .doOnNext(notificationSetup -> {
                        Log.v(TAG, "client successfully set up notifications");
                    })
                    .flatMap(observable -> {
                        InputStreamObserver inputStreamObserver = new InputStreamObserver();
                        observable.subscribe(inputStreamObserver);
                        return AdvertisePacket.parseFrom(inputStreamObserver).toObservable();
                    }).firstOrError()
                    .doOnSuccess(packet -> {
                        connection.createNewLongWriteBuilder()
                                .setBytes(advertisePacket.getBytes())
                                .setCharacteristic(ADVERTISE_CHARACTERISTIC)
                                .build()
                        .subscribe();
                        //TODO: do this in a less hacky way
                    });
        }

        public void close() {
            disposable.dispose();
        }
    }
}
