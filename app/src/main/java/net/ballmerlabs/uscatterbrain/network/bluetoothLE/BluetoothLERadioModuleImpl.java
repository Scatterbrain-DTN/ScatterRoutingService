package net.ballmerlabs.uscatterbrain.network.bluetoothLE;

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
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.ServerConfig;
import com.polidea.rxandroidble2.Timeout;
import com.polidea.rxandroidble2.scan.ScanFilter;
import com.polidea.rxandroidble2.scan.ScanSettings;

import net.ballmerlabs.uscatterbrain.API.HandshakeResult;
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent;
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.ElectLeaderPacket;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Optional;
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
    public static final int NUM_CHANNELS = 8;
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_SEMAPHOR = UUID.fromString("3429e76d-242a-4966-b4b3-301f28ac3ef2");
    public static final String WAKE_LOCK_TAG = "net.ballmerlabs.uscatterbrain::scatterbrainwakelock";
    public static final ConcurrentHashMap<UUID, LockedCharactersitic> channels = new ConcurrentHashMap<>();
    private final PowerManager powerManager;
    private final AtomicReference<PowerManager.WakeLock> wakeLock = new AtomicReference<>();


    public static final BluetoothGattService mService = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

    static {
        makeCharacteristic(UUID_SEMAPHOR);
        for (int i=0;i<NUM_CHANNELS;i++) {
            final UUID channel = incrementUUID(SERVICE_UUID, i+1);
            channels.put(channel, new LockedCharactersitic(makeCharacteristic(channel), i));
        }
    }

    public static UUID incrementUUID(UUID uuid, int i) {
        final ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        final BigInteger b = new BigInteger(buffer.array()).add(BigInteger.valueOf(i));
        final ByteBuffer out = ByteBuffer.wrap(b.toByteArray());
        final long high = out.getLong();
        final long low = out.getLong();
        return new UUID(high, low);
    }


    public static byte[] uuid2bytes(UUID uuid) {
        final ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    public static UUID bytes2uuid(byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final long high = buffer.getLong();
        final long low = buffer.getLong();
        return new UUID(high, low);
    }

    public static BluetoothGattCharacteristic makeCharacteristic(UUID uuid) {
        final BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ |
                        BluetoothGattCharacteristic.PROPERTY_WRITE |
                        BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE |
                        BluetoothGattCharacteristic.PERMISSION_READ
        );
        mService.addCharacteristic(characteristic);
        return characteristic;
    }


    private final CompositeDisposable mGattDisposable = new CompositeDisposable();
    private final Context mContext;
    private final Scheduler clientScheduler;
    private final int discoverDelay = 45;
    private final AtomicReference<Disposable> discoveryDispoable = new AtomicReference<>();
    private final ConcurrentHashMap<String, Observable<CachedLEConnection>> connectionCache = new ConcurrentHashMap<>();
    private final ScatterbrainDatastore datastore;
    private final PublishRelay<HandshakeResult> transactionCompleteRelay = PublishRelay.create();
    private final Set<UUID> connectedLuids = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicReference<UUID> myLuid = new AtomicReference<>(UUID.randomUUID());
    private final PublishRelay<Throwable> transactionErrorRelay = PublishRelay.create();
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

    @Inject
    public BluetoothLERadioModuleImpl(
            Context context,
            BluetoothLeAdvertiser advertiser,
            @Named(RoutingServiceComponent.NamedSchedulers.BLE_CLIENT) Scheduler bluetoothScheduler,
            RxBleServer rxBleServer,
            RxBleClient rxBleClient,
            WifiDirectRadioModule wifiDirectRadioModule,
            ScatterbrainDatastore datastore,
            PowerManager powerManager
            ) {
        mContext = context;
        mAdvertiser = advertiser;
        this.clientScheduler = bluetoothScheduler;
        this.powerManager = powerManager;
        this.mServer = rxBleServer;
        this.mClient = rxBleClient;
        this.wifiDirectRadioModule = wifiDirectRadioModule;
        this.datastore = datastore;
        observeTransactionComplete();
    }

    private void observeTransactionComplete() {
        Disposable d = this.transactionCompleteRelay.subscribe(
                next -> {
                    Log.v(TAG, "transaction complete, randomizing luid");
                    releaseWakeLock();
                    myLuid.set(UUID.randomUUID());
                },
                err -> Log.e(TAG, "error in transactionCompleteRelay " + err)
        );
        mGattDisposable.add(d);
    }

    protected void acquireWakelock() {
        wakeLock.updateAndGet(w -> {
            final PowerManager.WakeLock wake;
            if (w == null) {
                wake = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            } else {
                wake = w;
            }
            return wake;
        }).acquire(10*60*1000L /*10 minutes*/);
    }

    protected void releaseWakeLock() {
       final PowerManager.WakeLock w = wakeLock.getAndUpdate(wl -> null);
       if (w != null) {
           w.release();
       }
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

    private Single<LeDeviceSession> initializeProtocol(BluetoothDevice device) {
        Log.v(TAG, "initialize protocol");
        LeDeviceSession session = new LeDeviceSession(device, myLuid);

        session.addStage(
                TransactionResult.STAGE_LUID_HASHED,
                serverConn -> {
                    Log.v(TAG, "gatt server luid hashed stage");
                    return session.getLuidStage().getSelfHashed()
                            .flatMapCompletable(luidpacket -> {
                                session.getLuidStage().addPacket(luidpacket);
                                return serverConn.serverNotify(luidpacket);
                            }).toSingleDefault(Optional.empty());
                }
                , conn -> {
                    Log.v(TAG, "gatt client luid hashed stage");
                    return conn.readLuid()
                            .doOnError(err -> Log.e(TAG, "error while receiving luid packet: " + err))
                            .observeOn(clientScheduler)
                            .map(luidPacket -> {
                                synchronized (connectedLuids) {
                                    final UUID hashUUID = luidPacket.getHashAsUUID();
                                    if (connectedLuids.contains(hashUUID)) {
                                        Log.e(TAG, "device: " + device + " already connected");
                                        return new TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device);
                                    } else {
                                        connectedLuids.add(hashUUID);
                                    }
                                }
                                Log.v(TAG, "client handshake received hashed luid packet: " + luidPacket.getValCase());
                                session.getLuidStage().addPacket(luidPacket);
                                return new TransactionResult<>(TransactionResult.STAGE_LUID, device);
                            });
        });


        session.addStage(
                TransactionResult.STAGE_LUID,
                serverConn -> {
                    Log.v(TAG, "gatt server luid stage");
                    return session.getLuidStage().getSelf()
                            .flatMapCompletable(luidpacket -> {
                                session.getLuidStage().addPacket(luidpacket);
                                return serverConn.serverNotify(luidpacket);
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
                    return serverConn.serverNotify(AdvertiseStage.getSelf())
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
                    return serverConn.serverNotify(packet)
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
                                return serverConn.serverNotify(packet);
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
                                    return serverConn.serverNotify(upgradePacket)
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
        return Single.just(session);
    }

    private Single<HandshakeResult> bootstrapWifiP2p(BootstrapRequest bootstrapRequest) {
        return wifiDirectRadioModule.bootstrapFromUpgrade(bootstrapRequest)
                .doOnError(err -> {
                    Log.e(TAG, "wifi p2p upgrade failed: " + err);
                    err.printStackTrace();
                    transactionCompleteRelay.accept(
                            new HandshakeResult(
                                    0,
                                    0,
                                    ScatterbrainScheduler.TransactionStatus.STATUS_FAIL
                            )
                    );
                });
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
                .map(d -> new CachedLEConnection(d, channels))
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
                })
                .doOnSubscribe(discoveryDispoable::set);
    }

    private Observable<HandshakeResult> discover(final int timeout, final boolean forever) {
        return observeTransactions()
                .doOnSubscribe(disp -> {
                    mGattDisposable.add(disp);
                    Disposable d = discoverOnce()
                            .repeat()
                            .retry()
                            .doOnError(err -> Log.e(TAG, "error with initial handshake: " + err))
                            .subscribe(
                                    complete -> {
                                        Log.v(TAG, "handshake completed: " + complete);
                                    },
                                    err -> Log.e(TAG, "handshake failed: " + err + '\n' + Arrays.toString(err.getStackTrace()))
                            );
                    if (!forever) {
                        Disposable timeoutDisp = Completable.fromAction(() -> {
                        })
                                .delay(timeout, TimeUnit.SECONDS)
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
                    mGattDisposable.add(d);
                });
    }

    @Override
    public Completable discoverWithTimeout(final int timeout) {
        return discover(timeout, false)
                .firstOrError()
                .ignoreElement();
    }

    @Override
    public Observable<HandshakeResult> discoverForever() {
        return discover(0, true);
    }

    @Override
    public Disposable startDiscover(discoveryOptions opts) {
        return discover(discoverDelay, opts.equals(discoveryOptions.OPT_DISCOVER_FOREVER))
                .subscribe(
                        res -> Log.v(TAG, "discovery completed"),
                        err -> Log.v(TAG, "discovery failed: " + err)
                );
    }


    @Override
    public Completable awaitTransaction() {
        return Completable.mergeArray(
                transactionCompleteRelay.firstOrError().ignoreElement(),
                transactionErrorRelay.flatMapCompletable(Completable::error)
        );
    }


    @Override
    public Observable<HandshakeResult> observeTransactions() {
        return Observable.merge(
                transactionCompleteRelay,
                transactionErrorRelay.flatMap(Observable::error)
        );
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
                .concatMap(connectionRaw -> {
                    final CachedLEServerConnection connection = new CachedLEServerConnection(connectionRaw, channels);
                    RxBleDevice device = mClient.getBleDevice(connection.getConnection().getDevice().getAddress());

                    //don't attempt to initiate a reverse connection when we already initiated the outgoing connection
                    if (device == null) {
                        Log.e(TAG, "device " + connection.getConnection().getDevice().getAddress() + " was null in client");
                        return Observable.error(new IllegalStateException("device was null"));
                    }

                    acquireWakelock();

                    Log.d(TAG, "gatt server connection from " + connection.getConnection().getDevice().getAddress());
                    return initializeProtocol(connection.getConnection().getDevice())
                            .flatMapObservable(session -> {
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
                                                    .concatMap(result -> result)
                                                    .takeUntil(result -> {
                                                        return result.second.nextStage.equals(TransactionResult.STAGE_EXIT);
                                                    })
                                                    .doOnNext(transactionResult -> {
                                                        session.setStage(transactionResult.second.nextStage);
                                                    });

                                        })
                                        .takeUntil(result -> {
                                            return result.second.nextStage.equals(TransactionResult.STAGE_EXIT);
                                        })
                                        .filter(pair -> pair.second.hasResult() || pair.first.isPresent())
                                        .map(pair -> {
                                            if (pair.second.hasResult()) {
                                                return pair.second.getResult();
                                            } else if (pair.first.isPresent()) {
                                                return pair.first.get();
                                            } else {
                                                throw new IllegalStateException("this should never happen");
                                            }
                                        })
                                        .doOnError(err -> {
                                            Log.e(TAG, "stage " + session.getStage() + " error " + err);
                                            err.printStackTrace();
                                        })
                                        .onErrorResumeNext(Observable.never())
                                        .doFinally(() -> {
                                            Log.v(TAG, "stages complete, cleaning up");
                                            connection.dispose();
                                        });
                            });

                })
                .doOnDispose(() -> {
                    Log.e(TAG, "gatt server disposed");
                    transactionErrorRelay.accept(new IllegalStateException("gatt server disposed"));
                    stopServer();
                })
                .doOnNext(req -> Log.v(TAG, "handling bootstrap request: " + req.toString()))
                .flatMapSingle(this::bootstrapWifiP2p)
                .retry()
                .repeat()
                .doOnNext(s -> releaseWakeLock())
                .doOnError(err -> {
                    Log.e(TAG, "gatt server shut down with error: " + err);
                    err.printStackTrace();
                })
                .subscribe(transactionCompleteRelay);

        mGattDisposable.add(d);

        startAdvertise();

        return true;
    }

    public void cleanup(RxBleDevice device) {
        connectionCache.remove(device.getMacAddress());
    }

    public void stopServer() {
        mServer.closeServer();
        stopAdvertise();
    }

    public static class DeviceConnection {
        public final CachedLEConnection connection;
        public final BluetoothDevice device;
        public DeviceConnection(BluetoothDevice device, CachedLEConnection connection) {
            this.device = device;
            this.connection = connection;
        }
    }



    public static class LockedCharactersitic {
        protected final BluetoothGattCharacteristic characteristic;
        private final BehaviorRelay<Boolean> lockState = BehaviorRelay.create();
        private final int channel;

        public LockedCharactersitic(BluetoothGattCharacteristic characteristic, int channel) {
            this.characteristic = characteristic;
            this.channel = channel;
            lockState.accept(false);
        }

        public Single<OwnedCharacteristic> awaitCharacteristic() {
            return Single.just(asUnlocked())
                    .zipWith(lockState.filter(p -> !p).firstOrError(), (ch, lockstate) -> ch)
                    .map(ch -> {
                        lock();
                        return ch;
                    });
        }

        private OwnedCharacteristic asUnlocked() {
            return new OwnedCharacteristic(this);
        }

        public int getChannel() {
            return channel;
        }

        public UUID getUuid() {
            return characteristic.getUuid();
        }

        public synchronized void lock() {
            lockState.accept(true);
        }

        public synchronized void release() {
            lockState.accept(false);
        }

        private synchronized BluetoothGattCharacteristic getCharacteristic() {
            return characteristic;
        }

    }

    public static class OwnedCharacteristic {
        private final LockedCharactersitic lockedCharactersitic;
        private boolean released = false;
        private OwnedCharacteristic(LockedCharactersitic lock) {
            this.lockedCharactersitic = lock;
        }

        public synchronized void release() {
            released = true;
        }

        public synchronized BluetoothGattCharacteristic getCharacteristic() {
            if (released) {
                throw new ConcurrentModificationException();
            }
            lockedCharactersitic.release();;
            return lockedCharactersitic.getCharacteristic();
        }

        public UUID getUuid() {
            return lockedCharactersitic.getUuid();
        }
    }
}
