package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.ElectLeaderPacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.google.protobuf.ByteString;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.ServerConfig;
import com.polidea.rxandroidble2.Timeout;
import com.polidea.rxandroidble2.scan.ScanFilter;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import io.reactivex.Completable;
import io.reactivex.Flowable;
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
    public static final UUID UUID_CLK_DESCRIPTOR = UUID.fromString("cb882be2-d3ee-40e1-a40c-f485a598389f");

    public static final BluetoothGattService mService = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    public static final BluetoothGattCharacteristic ADVERTISE_CHARACTERISTIC = makeCharacteristic(UUID_ADVERTISE);
    public static final BluetoothGattCharacteristic UPGRADE_CHARACTERISTIC = makeCharacteristic(UUID_UPGRADE);
    public static final BluetoothGattCharacteristic LUID_CHARACTERISTIC = makeCharacteristic(UUID_LUID);
    public static final BluetoothGattCharacteristic ELECTION_CHARACTERISTIC = makeCharacteristic(UUID_ELECTIONLEADER);
    public static final BluetoothGattCharacteristic BOCKDATA_CHARACTERISTIC = makeCharacteristic(UUID_BLOCKDATA);
    public static final BluetoothGattCharacteristic BLOCKSEQUENCE_CHARACTERISTIC = makeCharacteristic(UUID_BLOCKSEQUENCE);


    private static final BlockHeaderPacket headerPacket = BlockHeaderPacket.newBuilder()
            .setApplication("fmef".getBytes())
            .setBlockSize(512)
            .setHashes(new ArrayList<>())
            .setSessionID(1)
            .setExtension("fmef")
            .setSig(ByteString.copyFrom(new byte[8]))
            .setToDisk(true)
            .setFromFingerprint(ByteString.copyFrom(new byte[8]))
            .setToFingerprint(ByteString.copyFrom(new byte[8]))
            .build();

    public static BluetoothGattCharacteristic makeCharacteristic(UUID uuid) {
        final BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ |
                        BluetoothGattCharacteristic.PROPERTY_WRITE |
                        BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE |
                        BluetoothGattCharacteristic.PERMISSION_READ
        );
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                UUID_CLK_DESCRIPTOR,
                BluetoothGattDescriptor.PERMISSION_WRITE
        );

        characteristic.addDescriptor(descriptor);
        mService.addCharacteristic(characteristic);
        return characteristic;
    }


    private final CompositeDisposable mGattDisposable = new CompositeDisposable();
    private final ConcurrentHashMap<String, LeDeviceSession<TransactionResult<BootstrapRequest>, Optional<BootstrapRequest>>> protocolSpec
            = new ConcurrentHashMap<>();
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
    private final WifiDirectRadioModule wifiDirectRadioModule;
    private final RxBleServer mServer;
    private final RxBleClient mClient;
    private AdvertisePacket mAdvertise;

    @Inject
    public BluetoothLERadioModuleImpl(
            Context context,
            BluetoothLeAdvertiser advertiser,
            @Named(RoutingServiceComponent.NamedSchedulers.BLE) Scheduler bluetoothScheduler,
            RxBleServer rxBleServer,
            RxBleClient rxBleClient,
            WifiDirectRadioModule wifiDirectRadioModule
            ) {
        mContext = context;
        mAdvertise = null;
        mAdvertiser = advertiser;
        this.bleScheduler = bluetoothScheduler;
        this.mServer = rxBleServer;
        this.mClient = rxBleClient;
        this.wifiDirectRadioModule = wifiDirectRadioModule;
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
        LeDeviceSession<TransactionResult<BootstrapRequest>, Optional<BootstrapRequest>> session = new LeDeviceSession<>(device, bleScheduler);

        session.addStage(
                TransactionResult.STAGE_LUID_HASHED,
                serverConn -> {
                    Log.v(TAG, "gatt server luid hashed stage");
                    return session.getLuidStage().getSelfHashed()
                            .flatMapCompletable(luidpacket -> {
                                session.getLuidStage().addPacket(luidpacket);
                                return serverConn.serverNotify(luidpacket, UUID_LUID);
                            }).toSingleDefault(Optional.empty());
                }
                , conn -> {
                    Log.v(TAG, "gatt client luid hashed stage");
                    return conn.readLuid()
                            .doOnSuccess(luidPacket -> {
                                Log.v(TAG, "client handshake received hashed luid packet: " + luidPacket.getValCase());
                                session.getLuidStage().addPacket(luidPacket);
                            })
                            .doOnError(err -> Log.e(TAG, "error while receiving luid packet: " + err))
                            .map(luidPacket -> new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_LUID, device));
        });


        session.addStage(
                TransactionResult.STAGE_LUID,
                serverConn -> {
                    Log.v(TAG, "gatt server luid stage");
                    return session.getLuidStage().getSelf()
                            .flatMapCompletable(luidpacket -> {
                                session.getLuidStage().addPacket(luidpacket);
                                return serverConn.serverNotify(luidpacket, UUID_LUID);
                            }).toSingleDefault(Optional.empty());
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
                            .toSingleDefault(new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_ADVERTISE, device))
                            .doOnError(err -> Log.e(TAG, "luid hash verify failed: " + err))
                            .onErrorReturnItem(new TransactionResult<>(TransactionResult.STAGE_EXIT, device));

                });

        session.addStage(
                TransactionResult.STAGE_ADVERTISE,
                serverConn -> {
                    Log.v(TAG, "gatt server advertise stage");
                    return serverConn.serverNotify(AdvertiseStage.getSelf(), UUID_ADVERTISE)
                            .toSingleDefault(Optional.empty());
                }
                , conn -> {
                    Log.v(TAG, "gatt client advertise stage");
                    return conn.readAdvertise()
                            .doOnSuccess(advertisePacket -> Log.v(TAG, "client handshake received advertise packet"))
                            .doOnError(err -> Log.e(TAG, "error while receiving advertise packet: " + err))
                            .map(advertisePacket -> {
                                session.getAdvertiseStage().addPacket(advertisePacket);
                                return new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_ELECTION_HASHED, device);
                            });
                });

        session.addStage(
                TransactionResult.STAGE_ELECTION_HASHED,
                serverConn -> {
                    Log.v(TAG, "gatt server election hashed stage");
                    ElectLeaderPacket packet = session.getVotingStage().getSelf(true);
                    session.getVotingStage().addPacket(packet);
                    return serverConn.serverNotify(packet, UUID_ELECTIONLEADER)
                            .toSingleDefault(Optional.empty());
                }
                , conn -> {
                    Log.v(TAG, "gatt client election hashed stage");
                    return conn.readElectLeader()
                            .doOnSuccess(electLeaderPacket -> Log.v(TAG, "client handshake received hashed election packet"))
                            .doOnError(err -> Log.e(TAG, "error while receiving election packet: " + err))
                            .map(electLeaderPacket -> {
                                session.getVotingStage().addPacket(electLeaderPacket);
                                return new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_ELECTION, device);
                            });
                });

        session.addStage(
                TransactionResult.STAGE_ELECTION,
                serverConn -> {
                    Log.v(TAG, "gatt server election stage");
                    return session.getLuidStage().getSelf()
                            .flatMapCompletable(luidPacket -> {
                                ElectLeaderPacket packet = session.getVotingStage().getSelf(false);
                                packet.tagLuid(luidPacket.getLuid());
                                session.getVotingStage().addPacket(packet);
                                return serverConn.serverNotify(packet, UUID_ELECTIONLEADER);
                            }).toSingleDefault(Optional.empty());
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
                                final ConnectionRole role;
                                if (session.getVotingStage().selectSeme().equals(session.getLuidStage().getLuid())) {
                                    role = ConnectionRole.ROLE_SEME;
                                } else {
                                    role = ConnectionRole.ROLE_UKE;
                                }
                                Log.v(TAG, "selected role: " + role);
                                session.setRole(role);
                                session.setUpgradeStage(provides);
                                if (provides.equals(AdvertisePacket.Provides.INVALID)) {
                                    Log.e(TAG, "received invalid provides");
                                    return new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device);
                                } else if (provides.equals(AdvertisePacket.Provides.BLE)) {
                                    Log.v(TAG, "blockdata not implemented, exiting");
                                    return new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device);
                                } else if (provides.equals(AdvertisePacket.Provides.WIFIP2P)){
                                    return new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_UPGRADE, device);
                                } else {
                                    return new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device);
                                }
                            })
                            .doOnError(err -> Log.e(TAG, "error while receiving packet: " + err))
                            .doOnSuccess(electLeaderPacket -> Log.v(TAG, "client handshake received election result"))
                            .onErrorReturn(err -> new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device));
                });

        session.addStage(
                TransactionResult.STAGE_UPGRADE,
                serverConn -> {
                    Log.v(TAG, "gatt server upgrade stage");
                    if (session.getRole().equals(ConnectionRole.ROLE_SEME)) {
                        return session.getUpgradeStage().getUpgrade()
                                .flatMap(upgradePacket -> {
                                    final BootstrapRequest request = WifiDirectBootstrapRequest.create(
                                                    upgradePacket,
                                                    ConnectionRole.ROLE_SEME
                                            );
                                    return serverConn.serverNotify(upgradePacket, UUID_UPGRADE)
                                            .toSingleDefault(Optional.of(request));
                                });

                    } else {
                        return Single.just(Optional.empty());
                    }
                }
                , conn -> {
                    Log.v(TAG, "gatt client upgrade stage");
                    if (session.getRole().equals(ConnectionRole.ROLE_UKE)) {
                        return conn.readUpgrade()
                                .doOnSuccess(electLeaderPacket -> Log.v(TAG, "client handshake received upgrade packet"))
                                .doOnError(err -> Log.e(TAG, "error while receiving upgrade packet: " + err))
                                .map(upgradePacket -> {
                                    BootstrapRequest request = WifiDirectBootstrapRequest.create(
                                            upgradePacket,
                                            ConnectionRole.ROLE_UKE
                                    );
                                    return new TransactionResult<>(TransactionResult.STAGE_EXIT, device, request);
                                });
                    } else {
                        return Single.just(new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device));
                    }
                });



        session.setStage(TransactionResult.STAGE_LUID_HASHED);
        protocolSpec.put(device.getAddress(), session);
    }

    private final Completable bootstrapWifiP2p(BootstrapRequest bootstrapRequest) {
        return wifiDirectRadioModule.bootstrapFromUpgrade(bootstrapRequest,
                Observable.just(new WifiDirectRadioModule.BlockDataStream(
                        headerPacket,
                        Flowable.empty()
                ))).ignoreElements();
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
                                    discoveryDispoable.getAndUpdate(compositeDisposable -> {
                                        if (compositeDisposable != null) {
                                            compositeDisposable.dispose();
                                        }
                                        return null;
                                    });
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

    @Override
    public boolean startServer() {
        if (mServer == null) {
            return false;
        }

        ServerConfig config = ServerConfig.newInstance(new Timeout(5, TimeUnit.SECONDS))
                .addService(mService);

        Disposable d = mServer.openServer(config)
                .doOnError(err -> Log.e(TAG, "failed to open server"))
                .flatMap(connectionRaw -> {
                    final CachedLEServerConnection connection = new CachedLEServerConnection(connectionRaw);
                    RxBleDevice device = mClient.getBleDevice(connection.getConnection().getDevice().getAddress());

                    //don't attempt to initiate a reverse connection when we already initiated the outgoing connection
                    if (device == null) {
                        Log.e(TAG, "device " + connection.getConnection().getDevice().getAddress() + " was null in client");
                        return Observable.error(new IllegalStateException("device was null"));
                    }

                    // only attempt to feed protocol to reverse connection if this is our first time
                    if (!protocolSpec.containsKey(device.getBluetoothDevice().getAddress())) {
                        initializeProtocol(device.getBluetoothDevice());
                    }

                    final LeDeviceSession<TransactionResult<BootstrapRequest>, Optional<BootstrapRequest>> session = protocolSpec.get(device.getMacAddress());

                    if (session == null) {
                        Log.e(TAG, "gatt session was null. Somethig is wrong");
                        return Observable.error(new IllegalStateException("session was null"));
                    }

                    Log.d(TAG, "gatt server connection from " + connection.getConnection().getDevice().getAddress());
                    return establishConnection(device, new Timeout(CLIENT_CONNECT_TIMEOUT, TimeUnit.SECONDS))
                            .onErrorResumeNext(Observable.never())
                            .flatMap(clientConnection -> {
                                return session.observeStage()
                                        .doOnNext(stage -> Log.v(TAG, "handling stage: " + stage))
                                        .flatMapSingle(stage -> {
                                            return Single.zip(
                                                    session.singleClient(),
                                                    session.singleServer(),
                                                    (client, server) -> {
                                                                return server.handshake(connection)
                                                                        .doOnSuccess(request -> Log.v(TAG, "server handshake completed"))
                                                                        .zipWith(
                                                                                client.handshake(clientConnection),
                                                                                Pair::new
                                                                        )
                                                                        .toObservable()
                                                                .doOnSubscribe(disposable -> Log.v("debug", "client handshake subscribed"));
                                                    }
                                            );
                                        })
                                        .flatMap(result -> result)
                                        .onErrorResumeNext(Observable.never())
                                        .doOnError(err -> {
                                            Log.e(TAG, "stage " + session.getStage() + " error " + err);
                                            err.printStackTrace();
                                        })
                                        .doOnNext(transactionResult -> {
                                            if (transactionResult.second.nextStage.equals(TransactionResult.STAGE_EXIT)) {
                                                cleanup(device);
                                            }
                                            session.setStage(transactionResult.second.nextStage);
                                        })
                                        .doFinally(() -> {
                                            Log.v(TAG, "stages complete, cleaning up");

                                        });

                            })
                            .doFinally(() -> {
                                Log.v(TAG, "session finished, cleaning up");
                                cleanup(device);
                            });


                })
                .doOnDispose(() -> {
                    Log.e(TAG, "gatt server disposed");
                    stopServer();
                })
                .flatMapCompletable(resultPair -> {
                    if (resultPair.second.hasResult()) {
                        return bootstrapWifiP2p(resultPair.second.getResult());
                    } else if (resultPair.first.isPresent()) {
                        return bootstrapWifiP2p(resultPair.first.get());
                    } else {
                        Log.e(TAG, "handshake failed, no result received");
                        return Completable.never();
                    }
                })
                .subscribe(
                        () -> Log.e(TAG, "gatt server completed. This should not happen"),
                        err -> {
                            Log.e(TAG, "gatt server shut down with error: " + err);
                            err.printStackTrace();
                        });

        mGattDisposable.add(d);

        startAdvertise();

        return true;
    }

    public void cleanup(RxBleDevice device) {
        protocolSpec.remove(device.getMacAddress());
        connectionCache.remove(device.getMacAddress());
        discoveryDispoable.getAndUpdate(compositeDisposable -> {
            if (compositeDisposable != null) {
                compositeDisposable.dispose();
            }
            return null;
        });
    }

    public void stopServer() {
        mServer.closeServer();
        stopAdvertise();
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
