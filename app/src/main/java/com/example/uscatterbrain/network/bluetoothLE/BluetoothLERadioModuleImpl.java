package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
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

import com.example.uscatterbrain.RoutingServiceComponent;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.polidea.rxandroidble2.LogConstants;
import com.polidea.rxandroidble2.LogOptions;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleConnection;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.ServerConfig;
import com.polidea.rxandroidble2.Timeout;
import com.polidea.rxandroidble2.scan.ScanFilter;
import com.polidea.rxandroidble2.scan.ScanResult;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.util.List;
import java.util.Map;
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
    private final CompositeDisposable mGattDisposable = new CompositeDisposable();
    private final Context mContext;
    private final Map<String, ServerPeerHandle> mServerPeers = new ConcurrentHashMap<>();
    private final Map<String,ClientPeerHandle> mClientPeers = new ConcurrentHashMap<>();
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
    };;
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

    private boolean isConnectedServer(BluetoothDevice device) {
        return mServerPeers.containsKey(device);
    }

    private boolean isConnectedClient(BluetoothDevice device) {
        return mClientPeers.containsKey(device);
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

    private Observable<RxBleConnection> getOrEstablishConnection(
            boolean autoconnect,
            Timeout timeout,
            ScanResult result
    ) {
        if (mClientPeers.containsKey(result.getBleDevice().getBluetoothDevice().getAddress())) {
            return Observable.just(
                    mClientPeers.get(result.getBleDevice().getBluetoothDevice().getAddress()).getConnection()
            );
        }

        if (result.getBleDevice().getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTING ||
                result.getBleDevice().getConnectionState() == RxBleConnection.RxBleConnectionState.CONNECTED) {
            return Observable.empty();
        }

        return result.getBleDevice().establishConnection(autoconnect, timeout)
                .map(connection -> {
                    Log.v(TAG, "LE connection successfully established.");
                    ClientPeerHandle peerHandle = new ClientPeerHandle(connection, mAdvertise, upgradePacketSubject);
                    mClientPeers.put(result.getBleDevice().getBluetoothDevice().getAddress(), peerHandle);
                    return connection;
                });
    }

    private Observable<RxBleConnection> discoverOnce() {
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
                    if (isConnectedServer(scanResult.getBleDevice().getBluetoothDevice())) {
                        Log.e(TAG, "device " + scanResult.getBleDevice().getMacAddress() + " already connected to server");
                        return Observable.empty();
                    }
                    return getOrEstablishConnection(
                            false,
                            new Timeout(CLIENT_CONNECT_TIMEOUT, TimeUnit.SECONDS),
                            scanResult
                    );
                });
    }

    @Override
    public void startDiscover(discoveryOptions opts) {
        Disposable d  = discoverOnce()
                .map(connection -> new ClientPeerHandle(connection, mAdvertise, upgradePacketSubject))
                .flatMap(ClientPeerHandle::handshake)
                .subscribeOn(bleScheduler)
                .subscribe(
                        complete -> Log.v(TAG, "handshake completed"),
                        err -> Log.e(TAG, "handshake failed: " + err)
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
                .subscribeOn(bleScheduler)
                .flatMap(connection -> {
                    Log.d(TAG, "gatt server connection from " + connection.getDevice().getAddress());
                    // we shouldn't maintain duplicate connections
                    if (isConnectedClient(connection.getDevice())) {
                        Log.d(TAG, "gatt server dropping duplicate connection: " + connection.getDevice().getAddress());
                        connection.disconnect();
                        return Observable.empty();
                    }
                    ServerPeerHandle handle = new ServerPeerHandle(connection, mAdvertise, upgradePacketSubject);
                    Disposable disconnect = connection.observeDisconnect()
                            .subscribe(dc -> mServerPeers.remove(connection.getDevice().getAddress()), error -> {
                                mServerPeers.remove(connection.getDevice().getAddress());
                                Log.e(TAG, "error when disconnecting device " + connection.getDevice());
                            });
                    mServerPeers.put(connection.getDevice().getAddress(), handle);
                    return handle.handshake().doFinally(() -> {
                        disconnect.dispose();
                        connection.disconnect();
                    });
                })
                .subscribe(packet -> {
                    Log.v(TAG, "gatt server successfully received packet");
                }, err -> Log.e(TAG, "error in gatt server handshake: " + err));

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
}
