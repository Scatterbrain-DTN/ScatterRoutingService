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
import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.polidea.rxandroidble2.LogConstants;
import com.polidea.rxandroidble2.LogOptions;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.ServerConfig;
import com.polidea.rxandroidble2.Timeout;
import com.polidea.rxandroidble2.internal.RxBleLog;
import com.polidea.rxandroidble2.scan.ScanFilter;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
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
    public static final UUID UUID_BLOCKDATA = UUID.fromString("9a27e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_BLOCKSEQUENCE = UUID.fromString("9a28e79f-4a6d-4e28-95c6-257f5e47fd90");

    private final BehaviorSubject<UpgradeRequest> upgradePacketSubject = BehaviorSubject.create();
    public static final BluetoothGattService mService = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    public static final BluetoothGattCharacteristic ADVERTISE_CHARACTERISTIC = makeCharacteristic(UUID_ADVERTISE);
    public static final BluetoothGattCharacteristic UPGRADE_CHARACTERISTIC = makeCharacteristic(UUID_UPGRADE);
    public static final BluetoothGattCharacteristic LUID_CHARACTERISTIC = makeCharacteristic(UUID_LUID);
    public static final BluetoothGattCharacteristic ELECTION_CHARACTERISTIC = makeCharacteristic(UUID_ELECTIONLEADER);
    public static final BluetoothGattCharacteristic BOCKDATA_CHARACTERISTIC = makeCharacteristic(UUID_BLOCKDATA);
    public static final BluetoothGattCharacteristic BLOCKSEQUENCE_CHARACTERISTIC = makeCharacteristic(UUID_BLOCKSEQUENCE);


    public static BluetoothGattCharacteristic makeCharacteristic(UUID uuid) {
        final BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ |
                        BluetoothGattCharacteristic.PROPERTY_WRITE |
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE |
                        BluetoothGattCharacteristic.PERMISSION_READ
        );
        mService.addCharacteristic(characteristic);
        return characteristic;
    }


    private final CompositeDisposable mGattDisposable = new CompositeDisposable();
    private final ConcurrentHashMap<String, LeDeviceSession<TransactionResult>> protocolSpec = new ConcurrentHashMap<>();
    private final Context mContext;
    private final Scheduler bleScheduler;
    private final int discoverDelay = 45;
    private final boolean discovering = true;
    private final AtomicReference<Disposable> discoveryDispoable = new AtomicReference<>();
    private final ConcurrentHashMap<String, Observable<CachedLEConnection>> connectionCache = new ConcurrentHashMap<>();
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
    private final BluetoothLeAdvertiser mAdvertiser;
    private final RxBleServer mServer;
    private final RxBleClient mClient;
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
        this.bleScheduler = bluetoothScheduler;
        this.mServer = rxBleServer;
        this.mClient = rxBleClient;
        RxBleLog.updateLogOptions(new LogOptions.Builder().setLogLevel(LogConstants.DEBUG).build());
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
        LeDeviceSession<TransactionResult> session = new LeDeviceSession<>(device, bleScheduler);

        session.addStage(
                LeDeviceSession.STAGE_LUID_HASHED,
                serverConn -> {
                    Log.v(TAG, "gatt server luid hashed stage");
                    return session.getLuidStage().getSelfHashed()
                            .flatMapCompletable(luidpacket -> serverConn.serverNotify(luidpacket, UUID_LUID));
                }
                , conn -> {
                    Log.v(TAG, "gatt client luid hashed stage");
                    return conn.readLuid()
                            .doOnSuccess(luidPacket -> {
                                Log.v(TAG, "client handshake received hashed luid packet: " + luidPacket.getValCase());
                                session.getLuidStage().addPacket(luidPacket);
                            })
                            .doOnError(err -> Log.e(TAG, "error while receiving luid packet: " + err))
                            .map(luidPacket -> new TransactionResult(LeDeviceSession.STAGE_LUID, device));
        });


        session.addStage(
                LeDeviceSession.STAGE_LUID,
                serverConn -> {
                    Log.v(TAG, "gatt server luid stage");
                    return session.getLuidStage().getSelf()
                            .flatMapCompletable(luidpacket -> serverConn.serverNotify(luidpacket, UUID_LUID));
                }
                , conn -> {
                    Log.v(TAG, "gatt client luid stage");
                    return conn.readLuid()
                            .doOnSuccess(luidPacket -> {
                                Log.v(TAG, "client handshake received unhashed luid packet: " + luidPacket.getLuid());
                                session.getLuidStage().addPacket(luidPacket);
                            })
                            .doOnError(err -> Log.e(TAG, "error while receiving luid packet: " + err))
                            .flatMapCompletable(luidPacket ->
                                    session.getLuidStage().verifyPackets()
                                    .doOnComplete(() -> session.getLuidMap().put(device.getAddress(), luidPacket.getLuid()))
                            )
                            .toSingleDefault(new TransactionResult(LeDeviceSession.STAGE_ADVERTISE, device))
                            .doOnError(err -> Log.e(TAG, "luid hash verify failed: " + err))
                            .onErrorReturnItem(new TransactionResult(LeDeviceSession.STAGE_EXIT, device));

                });

        session.addStage(
                LeDeviceSession.STAGE_ADVERTISE,
                serverConn -> {
                    Log.v(TAG, "gatt server advertise stage");
                    return serverConn.serverNotify(AdvertiseStage.getSelf(), UUID_ADVERTISE);
                }
                , conn -> {
                    Log.v(TAG, "gatt client advertise stage");
                    return conn.readAdvertise()
                            .doOnSuccess(advertisePacket -> Log.v(TAG, "client handshake received advertise packet"))
                            .doOnError(err -> Log.e(TAG, "error while receiving advertise packet: " + err))
                            .map(advertisePacket -> {
                                session.getAdvertiseStage().addPacket(advertisePacket);
                                return new TransactionResult(LeDeviceSession.STAGE_ELECTION_HASHED, device);
                            });
                });

        session.addStage(
                LeDeviceSession.STAGE_ELECTION_HASHED,
                serverConn -> {
                    Log.v(TAG, "gatt server election hashed stage");
                    return serverConn.serverNotify(session.getVotingStage().getSelf(true), UUID_ELECTIONLEADER);
                }
                , conn -> {
                    Log.v(TAG, "gatt client election hashed stage");
                    return conn.readElectLeader()
                            .doOnSuccess(electLeaderPacket -> Log.v(TAG, "client handshake received hashed election packet"))
                            .doOnError(err -> Log.e(TAG, "error while receiving election packet: " + err))
                            .map(electLeaderPacket -> {
                                session.getVotingStage().addPacket(electLeaderPacket);
                                return new TransactionResult(LeDeviceSession.STAGE_ELECTION, device);
                            });
                });

        session.addStage(
                LeDeviceSession.STAGE_ELECTION,
                serverConn -> {
                    Log.v(TAG, "gatt server election stage");
                    return serverConn.serverNotify(session.getVotingStage().getSelf(false), UUID_ELECTIONLEADER);
                }
                , conn -> {
                    Log.v(TAG, "gatt client election stage");
                    return conn.readElectLeader()
                            .flatMapCompletable(electLeaderPacket -> {
                                electLeaderPacket.tagLuid(session.getLuidMap().get(device.getAddress()));
                                session.getVotingStage().addPacket(electLeaderPacket);
                                return session.getVotingStage().verifyPackets();
                            })
                            .andThen(session.getVotingStage().determineUpgrade())
                            .map(provides -> {
                                Log.v(TAG, "election received provides: " + provides);
                                return new TransactionResult(LeDeviceSession.STAGE_EXIT, device);
                            })
                            .doOnError(err -> Log.e(TAG, "error while receiving packet: " + err))
                            .doOnSuccess(electLeaderPacket -> Log.v(TAG, "client handshake received election result"))
                            .onErrorReturn(err -> new TransactionResult(LeDeviceSession.STAGE_EXIT, device));
                });


        session.setStage(LeDeviceSession.STAGE_LUID_HASHED);
        protocolSpec.put(device.getAddress(), session);
    }

    private Observable<CachedLEConnection> establishConnection(RxBleDevice device, Timeout timeout) {

        Observable<CachedLEConnection> conn = connectionCache.get(device.getMacAddress());
        if (conn != null) {
            return conn;
        }
        BehaviorSubject<CachedLEConnection> subject = BehaviorSubject.create();
        connectionCache.put(device.getMacAddress(), subject);
        return device.establishConnection(false, timeout)
                .doOnDispose(() -> connectionCache.remove(device.getMacAddress()))
                .doOnError(err -> connectionCache.remove(device.getMacAddress()))
                .map(CachedLEConnection::new)
                .doOnNext(connection -> {
                    Log.v(TAG, "successfully established connection");
                    subject.onNext(connection);
                });
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
                    return establishConnection(
                            scanResult.getBleDevice(),
                            new Timeout(CLIENT_CONNECT_TIMEOUT, TimeUnit.SECONDS)
                    )
                            .map(connection -> new DeviceConnection(
                                    scanResult.getBleDevice().getBluetoothDevice(),
                                    connection
                            ));
                });
    }

    @Override
    public void startDiscover(discoveryOptions opts) {
        Disposable d  = discoverOnce()
                .doOnError(err -> Log.e(TAG, "error with initial handshake: " + err))
                .subscribe(
                        complete -> {
                            Log.v(TAG, "handshake completed: " + complete);
                        },
                        err -> Log.e(TAG, "handshake failed: " + err + '\n' + Arrays.toString(err.getStackTrace()))
                );
        discoveryDispoable.set(d);

        if (opts == discoveryOptions.OPT_DISCOVER_ONCE) {
            Disposable timeoutDisp = Completable.fromAction(() -> {})
                    .delay(discoverDelay, TimeUnit.SECONDS)
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
        }
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
                .doOnError(err -> Log.e(TAG, "failed to open server"))
                .onErrorResumeNext(mServer.openServer(config))
                .flatMapCompletable(connectionRaw -> {
                    final CachedLEServerConnection connection = new CachedLEServerConnection(connectionRaw);
                    final CompositeDisposable connectionDisposable = new CompositeDisposable();

                    RxBleDevice device = mClient.getBleDevice(connection.getConnection().getDevice().getAddress());

                    //don't attempt to initiate a reverse connection when we already initiated the outgoing connection
                    if (device == null) {
                        Log.e(TAG, "device " + connection.getConnection().getDevice().getAddress() + " was null in client");
                        return Completable.error(new IllegalStateException("device was null"));
                    }

                    // only attempt to feed protocol to reverse connection if this is our first time
                    if (!protocolSpec.containsKey(device.getBluetoothDevice().getAddress())) {
                        initializeProtocol(device.getBluetoothDevice());
                    }

                    final LeDeviceSession<TransactionResult> session = protocolSpec.get(device.getMacAddress());

                    if (session == null) {
                        Log.e(TAG, "gatt session was null. Somethig is wrong");
                        return Completable.error(new IllegalStateException("session was null"));
                    }

                    Log.d(TAG, "gatt server connection from " + connection.getConnection().getDevice().getAddress());
                    return establishConnection(device, new Timeout(CLIENT_CONNECT_TIMEOUT, TimeUnit.SECONDS))
                            .flatMap(clientConnection -> {
                                return session.observeStage()
                                        .doOnNext(stage -> Log.v(TAG, "handling stage: " + stage))
                                        .flatMapSingle(stage -> {
                                            return Single.zip(
                                                    session.singleClient(),
                                                    session.singleServer(),
                                                    (client, server) -> {
                                                        return client.handshake(clientConnection)
                                                                .doOnSubscribe(disposable -> {
                                                                    Log.v("debug", "client handshake subscribed");
                                                                    connectionDisposable.add(disposable);
                                                                    Disposable serverDisposable = server.handshake(connection)
                                                                            .subscribe(
                                                                                    () -> Log.v(TAG, "server handshake success"),
                                                                                    err -> Log.e(TAG, "server handshake failure " + err)
                                                                            );

                                                                    connectionDisposable.add(disposable);
                                                                    connectionDisposable.add(serverDisposable);

                                                                });
                                                    }
                                            );
                                        })
                                        .flatMapSingle(result -> result)
                                        .doOnNext(transactionResult -> session.setStage(transactionResult.nextStage));

                            })
                            .ignoreElements();

                })
                .subscribe(() -> {
                    Log.v(TAG, "gatt server shut down with success");
                }, err -> {
                    Log.e(TAG, "gatt server shut down with error: " + err);
                    err.printStackTrace();
                });


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
        public final CachedLEConnection connection;
        public final BluetoothDevice device;
        public DeviceConnection(BluetoothDevice device, CachedLEConnection connection) {
            this.device = device;
            this.connection = connection;
        }
    }
}
