package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.Pair;

import com.example.uscatterbrain.RoutingServiceComponent;
import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.polidea.rxandroidble2.LogConstants;
import com.polidea.rxandroidble2.LogOptions;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.ServerConfig;
import com.polidea.rxandroidble2.Timeout;
import com.polidea.rxandroidble2.scan.ScanFilter;
import com.polidea.rxandroidble2.scan.ScanResult;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;

@Singleton
public class BluetoothLERadioModuleImpl implements BluetoothLEModule {
    public static final String TAG = "BluetoothLE";
    public static final int CLIENT_CONNECT_TIMEOUT = 10;
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_ADVERTISE = UUID.fromString("9a22e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_UPGRADE =  UUID.fromString("9a24e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_LUID = UUID.fromString("9a25e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_ELECTIONLEADER = UUID.fromString("9a26e79f-4a6d-4e28-95c6-257f5e47fd90");
    private final BehaviorSubject<UpgradeRequest> upgradePacketSubject = BehaviorSubject.create();
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

    public static final BluetoothGattCharacteristic LUID_CHARACTERISTIC = new BluetoothGattCharacteristic(
            UUID_LUID,
            BluetoothGattCharacteristic.PROPERTY_READ |
                    BluetoothGattCharacteristic.PROPERTY_WRITE |
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE |
                    BluetoothGattCharacteristic.PERMISSION_READ
    );

    public static final BluetoothGattCharacteristic ELECTION_CHARACTERISTIC = new BluetoothGattCharacteristic(
            UUID_ELECTIONLEADER,
            BluetoothGattCharacteristic.PROPERTY_READ |
                    BluetoothGattCharacteristic.PROPERTY_WRITE |
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE |
                    BluetoothGattCharacteristic.PERMISSION_READ
    );



    private final CompositeDisposable mGattDisposable = new CompositeDisposable();
    private final ConcurrentHashMap<String, Queue<ClientPeerHandle>> protocolSpec = new ConcurrentHashMap<>();
    private final Context mContext;
    private final Set<String> mClientSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Scheduler bleScheduler;
    private int discoverDelay = 45;
    private boolean discovering = true;
    private final AtomicReference<Disposable> discoveryDispoable = new AtomicReference<>();
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
    };
    private BluetoothLeAdvertiser mAdvertiser;
    private RxBleServer mServer;
    private RxBleClient mClient;
    private AdvertisePacket mAdvertise;

    @Inject
    public BluetoothLERadioModuleImpl(
            Context context,
            BluetoothLeAdvertiser advertiser,
            @Named(RoutingServiceComponent.NamedSchedulers.BLE) Scheduler bluetoothScheduler,
            RxBleServer rxBleServer,
            RxBleClient rxBleClient
            ) {
        mContext = context;
        mAdvertise = null;
        mAdvertiser = advertiser;
        mService.addCharacteristic(ADVERTISE_CHARACTERISTIC);
        mService.addCharacteristic(UPGRADE_CHARACTERISTIC);
        this.bleScheduler = bluetoothScheduler;
        this.mServer = rxBleServer;
        this.mClient = rxBleClient;
        RxBleClient.updateLogOptions(new LogOptions.Builder()
        .setLogLevel(LogConstants.DEBUG).build());
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
    public void startAdvertise() {
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

            mAdvertiser.startAdvertising(settings, addata, mAdvertiseCallback);

        }
    }

    @Override
    public void stopAdvertise() {
        Log.v(TAG, "stopping LE advertise");
        mAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    private void initializeProtocol(BluetoothDevice device) {
        Log.v(TAG, "initialize protocol");
        Queue<ClientPeerHandle> protocolQueue = new LinkedList<>();
        protocolQueue.add(new ClientPeerHandle() {
            @Override
            public Single<Boolean> handshake(RxBleConnection conn) {
                Log.v(TAG, "client handshake called");
                return readAdvertise(conn)
                        .ignoreElement()
                        .doOnComplete(() -> Log.v(TAG, "client handshake received advertise packet"))
                        .andThen(readUpgrade(conn))
                        //.doOnSuccess(upgradePacket -> upgradeSubject.onNext(BluetoothLEModule.UpgradeRequest.create()))
                        .ignoreElement()
                        .doOnComplete(() -> Log.v(TAG, "client handshake received upgrade packet"))
                        .toSingleDefault(true);
            }
        });

        protocolSpec.put(device.getAddress(), protocolQueue);
    }

    private Observable<RxBleConnection> establishConnection(RxBleDevice device, Timeout timeout) {
        return device.establishConnection(false, timeout)
                .doOnNext(connection -> Log.v(TAG, "successfully established connection"));
    }

    private Observable<RxBleConnection> firstTimeOutgoingConnection(
            Timeout timeout,
            ScanResult result
    ) {
        initializeProtocol(result.getBleDevice().getBluetoothDevice());
        mClientSet.add(result.getBleDevice().getMacAddress());
        return establishConnection(result.getBleDevice(), timeout)
                .doFinally(() -> mClientSet.remove(result.getBleDevice().getMacAddress()));
    }

    private Observable<DeviceConnection> discoverOnce() {
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
                .concatMap(scanResult -> {
                    Log.d(TAG, "scan result: " + scanResult.getBleDevice().getMacAddress());
                    return firstTimeOutgoingConnection(
                            new Timeout(CLIENT_CONNECT_TIMEOUT, TimeUnit.SECONDS),
                            scanResult
                    )
                            .map(connection -> new DeviceConnection(
                                    scanResult.getBleDevice().getBluetoothDevice(),
                                    connection
                            ))
                            .onErrorResumeNext(Observable.empty());
                });
    }

    @Override
    public void startDiscover(discoveryOptions opts) {
        Disposable d  = discoverOnce()
                .map(connection -> new Pair<ClientPeerHandle, RxBleConnection>(
                        protocolSpec.get(connection.device.getAddress()).remove(),
                        connection.connection
                ))
                .doOnError(err -> Log.e(TAG, "error with initial handshake: " + err))
                .onErrorResumeNext(Observable.empty())
                .flatMapSingle(connectionPair -> connectionPair.first.handshake(connectionPair.second))
                .subscribeOn(bleScheduler)
                .subscribe(
                        complete -> Log.v(TAG, "handshake completed: " + complete),
                        err -> Log.e(TAG, "handshake failed: " + err + '\n' + Arrays.toString(err.getStackTrace()))
                );
        discoveryDispoable.set(d);

        if (opts == discoveryOptions.OPT_DISCOVER_ONCE) {
            Disposable timeoutDisp = Completable.fromAction(() -> {})
                    .delay(discoverDelay, TimeUnit.SECONDS)
                    .subscribeOn(bleScheduler)
                    .subscribe(
                            () -> {
                                Log.v(TAG, "scan timed out");
                                    Disposable disposable = discoveryDispoable.get();
                                    if (disposable != null) {
                                        disposable.dispose();
                                        discoveryDispoable.set(null);
                                    }
                            },
                            err -> Log.e(TAG, "error while timing out scan: " + err)
                    );
            mGattDisposable.add(timeoutDisp);
        };
    }

    @Override
    public void stopDiscover(){
        Disposable d = discoveryDispoable.get();
        if (d != null) {
            d.dispose();
        }
    }


    private UpgradePacket getUpgradePacket() {
        int seqnum = Math.abs(new Random().nextInt());

        return UpgradePacket.newBuilder()
                .setProvides(ScatterProto.Advertise.Provides.WIFIP2P)
                .setSessionID(seqnum)
                .setMetadata(WifiDirectRadioModule.UPGRADE_METADATA)
                .build();
    }

    @Override
    public Observable<UpgradeRequest> getOnUpgrade() {
        return upgradePacketSubject;
    }

    @Override
    public boolean startServer() {
        if (mServer == null) {
            return false;
        }

        ServerConfig config = ServerConfig.newInstance(new Timeout(5, TimeUnit.SECONDS))
                .addService(mService);

        Disposable d = mServer.openServer(config)
                .onErrorResumeNext(mServer.openServer(config))
                .subscribeOn(bleScheduler)
                .flatMapCompletable(connection -> {
                    final CompositeDisposable connectionDisposable = new CompositeDisposable();

                    Disposable disconnect = connection.observeDisconnect()
                            .subscribe(
                                    dc -> Log.v(TAG, "disconnected server peer"),
                                    error -> Log.e(TAG, "error when disconnecting device " + connection.getDevice()));

                    connectionDisposable.add(disconnect);

                    RxBleDevice device = mClient.getBleDevice(connection.getDevice().getAddress());

                    // only attempt to feed protocol to reverse connection if this is our first time
                    if (!protocolSpec.containsKey(device.getBluetoothDevice().getAddress())) {
                        initializeProtocol(device.getBluetoothDevice());
                    }
                    //don't attempt to initiate a reverse connection when we already initiated the outgoing connection
                    if (device == null) {
                        Log.e(TAG, "device " + connection.getDevice().getAddress() + " was null in client");
                        return Completable.error(new IllegalStateException("device was null"));
                    }

                    ServerPeerHandle handle = new ServerPeerHandle(connection);
                    handle.serverNotify(getUpgradePacket(), UPGRADE_CHARACTERISTIC);
                    handle.serverNotify(mAdvertise, ADVERTISE_CHARACTERISTIC);
                    handle.setDefaultReply(ADVERTISE_CHARACTERISTIC, BluetoothGatt.GATT_SUCCESS);
                    handle.setDefaultReply(UPGRADE_CHARACTERISTIC, BluetoothGatt.GATT_SUCCESS);
                    handle.setDefaultReply(ELECTION_CHARACTERISTIC, BluetoothGatt.GATT_SUCCESS);
                    handle.setDefaultReply(LUID_CHARACTERISTIC, BluetoothGatt.GATT_SUCCESS);


                    if(
                            protocolSpec.getOrDefault( device.getBluetoothDevice().getAddress(), new LinkedList<>()).size() == 0
                    ) {
                        Log.v(TAG, "protocol queue empty, terminating transaction");
                        protocolSpec.remove(device.getBluetoothDevice().getAddress());
                        return Completable.never();
                    }

                    Log.d(TAG, "gatt server connection from " + connection.getDevice().getAddress());
                    return establishConnection(device, new Timeout(30, TimeUnit.SECONDS))
                            .map(conn ->
                                    new Pair<>(protocolSpec.get(device.getBluetoothDevice().getAddress()).remove(), conn)
                            )
                            .doOnError(err -> Log.v(TAG, "error in reverse connection: " + err))
                            .flatMapSingle(connectionPair -> connectionPair.first.handshake(connectionPair.second))
                            .subscribeOn(bleScheduler)
                            .takeWhile(aBoolean -> ! mClientSet.contains(connection.getDevice().getAddress()))
                            .firstOrError()
                            .ignoreElement()
                            .doOnError(err -> Log.e(TAG, "failed reverse connection: " + err))
                            .onErrorComplete()
                            .doFinally(() -> {
                                Log.v(TAG, "freeing resources");
                                connection.disconnect();
                                connectionDisposable.dispose();
                                handle.close();
                            });
                })
                .subscribe(() -> {
                    Log.v(TAG, "gatt server shut down with success");
                }, err -> Log.e(TAG, "gatt server shut down with error: " + err));

        mGattDisposable.add(d);

        startAdvertise();

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


    public static class DeviceConnection {
        public final RxBleConnection connection;
        public final BluetoothDevice device;
        public DeviceConnection(BluetoothDevice device, RxBleConnection connection) {
            this.device = device;
            this.connection = connection;
        }
    }
}
