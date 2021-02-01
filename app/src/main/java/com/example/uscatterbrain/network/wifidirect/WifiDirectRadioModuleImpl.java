package com.example.uscatterbrain.network.wifidirect;

import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.uscatterbrain.RoutingServiceComponent;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.DeclareHashesPacket;
import com.example.uscatterbrain.network.IdentityPacket;
import com.example.uscatterbrain.network.RoutingMetadataPacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.bluetoothLE.BootstrapRequest;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.CompletableSubject;
import io.reactivex.subjects.ReplaySubject;

@RequiresApi(api = Build.VERSION_CODES.Q)
@Singleton
public class WifiDirectRadioModuleImpl implements WifiDirectRadioModule {
    private final WifiP2pManager mManager;
    private final WifiDirectBroadcastReceiver mBroadcastReceiver;
    private final WifiP2pManager.Channel mP2pChannel;
    private final Scheduler readScheduler;
    private final Scheduler writeScheduler;
    private final Scheduler operationsScheduler;
    private final ScatterbrainDatastore datastore;
    private final Context mContext;
    private static final int SCATTERBRAIN_PORT = 7575;
    private static final InterceptableServerSocket.InterceptableServerSocketFactory socketFactory =
            new InterceptableServerSocket.InterceptableServerSocketFactory();
    private static final AtomicReference<Boolean> groupOperationInProgress = new AtomicReference<>();
    private static final AtomicReference<Boolean> groupConnectInProgress = new AtomicReference<>();

    private static final CompositeDisposable wifidirectDisposable = new CompositeDisposable();
    private static final CompositeDisposable tcpServerDisposable = new CompositeDisposable();

    @Inject
    public WifiDirectRadioModuleImpl(
            WifiP2pManager manager,
            Context context,
            ScatterbrainDatastore datastore,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_READ) Scheduler readScheduler,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_WRITE) Scheduler writeScheduler,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_OPERATIONS) Scheduler operationsScheduler
    ) {
        this.mContext = context;
        this.mManager = manager;
        this.mP2pChannel = manager.initialize(context, context.getMainLooper(), null);
        this.mBroadcastReceiver = new WifiDirectBroadcastReceiver(manager, mP2pChannel, context);
        this.readScheduler = readScheduler;
        this.writeScheduler = writeScheduler;
        this.operationsScheduler = operationsScheduler;
        this.datastore = datastore;
        groupOperationInProgress.set(false);
        groupConnectInProgress.set(false);
        Disposable d = mBroadcastReceiver.observeConnectionInfo()
                .subscribe(
                        info -> {
                            Log.v(TAG, "connection state change: " + info.toString());
                            if (info.groupFormed && info.isGroupOwner) {
                                //TODO:
                            } else if (info.groupFormed) {

                            }
                        },
                        err -> Log.v(TAG, "error on state change: " + err)
                );

        Disposable d2 = mBroadcastReceiver.observeP2pState()
                .subscribe(
                        state -> {
                            Log.v(TAG, "p2p state change: " + state.toString());
                            if (state == WifiDirectBroadcastReceiver.P2pState.STATE_DISABLED) {
                                Log.v(TAG, "adapter disabled, disposing tcp server");
                                tcpServerDisposable.dispose();
                            }
                        },
                        err -> Log.e(TAG, "error on p2p state change: " + err)
                );

        Disposable d3 = mBroadcastReceiver.observePeers()
                .subscribe(
                        success -> Log.v(TAG, "peers changed: " + success.toString()),
                        err -> Log.e(TAG, "error when fetching peer list: " + err)
                );

        Disposable d4 = mBroadcastReceiver.observeThisDevice()
                .subscribe(
                        success -> Log.v(TAG, "this device changed: " + success.toString()),
                        err -> Log.e(TAG, "error during this device change: " + err)
                );

        wifidirectDisposable.add(d);
        wifidirectDisposable.add(d2);
        wifidirectDisposable.add(d3);
        wifidirectDisposable.add(d4);

        //TODO: unregister this when appropriate
        registerBroadcastReceiver();

        Disposable tcpserverdisposable = socketFactory.create(SCATTERBRAIN_PORT)
                .flatMapObservable(InterceptableServerSocket::acceptLoop)
                .subscribeOn(operationsScheduler)
                .doOnComplete(() -> Log.e(TAG, "tcp server completed. fueee"))
                .subscribe(
                        socket -> Log.v(TAG,"accepted socket: " + socket.getSocket()),
                        err -> Log.e(TAG, "error when accepting socket: " + err)
                );

        wifidirectDisposable.add(tcpserverdisposable);
    }


    private void registerBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);

        // Indicates a change in the list of available peers.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);

        // Indicates the state of Wi-Fi P2P connectivity has changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        // Indicates this device's details have changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        mContext.registerReceiver(mBroadcastReceiver.asReceiver(), intentFilter);
    }

    public Completable createGroup(String name, String passphrase) {
        return Single.fromCallable(() -> {
            Log.v(TAG, "creategroup called" + name + " " + passphrase);
            try {
                final WifiP2pConfig config = new WifiP2pConfig.Builder()
                        .setNetworkName(name)
                        .setPassphrase(passphrase)
                        .build();
                final ReplaySubject<Object> subject = ReplaySubject.create();
                final AtomicReference<Integer> groupRetry = new AtomicReference<>(5);
                if (!groupOperationInProgress.getAndUpdate(val -> true)) {
                    final WifiP2pManager.ActionListener listener = new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.v(TAG, "successfully created group!");
                            groupOperationInProgress.set(false);
                            subject.onComplete();
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.e(TAG, "failed to create group: " + reasonCodeToString(reason));
                            if (groupRetry.getAndUpdate(val -> --val) > 0) {
                                mManager.createGroup(mP2pChannel, this);
                            } else {
                                subject.onError(new IllegalStateException("failed to create group"));
                            }
                        }
                    };
                    mManager.requestGroupInfo(mP2pChannel, group -> {
                        if (group == null) {
                            Log.v(TAG, "group is null, assuming not created");
                            mManager.createGroup(mP2pChannel, config, listener);
                        } else {
                            mManager.removeGroup(mP2pChannel, new WifiP2pManager.ActionListener() {
                                @Override
                                public void onSuccess() {
                                    mManager.createGroup(mP2pChannel, config, listener);
                                }

                                @Override
                                public void onFailure(int reason) {
                                    Log.e(TAG, "failed to remove group");
                                    groupOperationInProgress.set(false);
                                    subject.onError(new IllegalStateException("failed to remove group"));
                                }
                            });
                        }
                    });
                } else {
                    subject.onComplete();
                }
                return subject
                        .ignoreElements()
                        .andThen(mBroadcastReceiver.observeConnectionInfo()
                        .takeUntil(wifiP2pInfo -> wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner)
                        .ignoreElements())
                        .doOnComplete(() -> Log.v(TAG, "createGroup return success"))
                        .doFinally(() -> groupOperationInProgress.set(false));
            } catch (SecurityException e) {
                return Completable.error(e);
            }
        }).flatMapCompletable(completable -> completable
                .timeout(5, TimeUnit.SECONDS));
    }

    private static Single<Socket> getTcpSocket(InetAddress address) {
        return Single.fromCallable(() -> new Socket(address, SCATTERBRAIN_PORT));
    }

    public static String reasonCodeToString(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY: {
                return "Busy";
            }
            case WifiP2pManager.ERROR: {
                return "Error";
            }
            case WifiP2pManager.P2P_UNSUPPORTED: {
                return "P2p unsupported";
            }
            default: {
                return "Unknown code: " + reason;
            }
        }
    }

    @Override
    public Single<WifiP2pInfo> connectToGroup(String name, String passphrase, int timeout) {
        if (!groupConnectInProgress.getAndUpdate(val -> true)) {
            WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setPassphrase(passphrase)
                    .setNetworkName(name)
                    .build();
            return retryDelay(initiateConnection(config), 20, 1)
                    .andThen(awaitConnection(timeout));
        } else {
            return awaitConnection(timeout);
        }
    }

    public Completable discoverPeers() {
        return Single.fromCallable(() -> {
            final CompletableSubject subject = CompletableSubject.create();
            final AtomicReference<Integer> discoverretry = new AtomicReference<>(20);

            try {
                final WifiP2pManager.ActionListener discoveryListener = new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.v(TAG, "peer discovery request completed, initiating connection");
                        subject.onComplete();
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "peer discovery failed");
                        if (discoverretry.getAndUpdate(val -> --val) > 0) {
                            mManager.discoverPeers(mP2pChannel, this);
                        } else {
                            subject.onError(new IllegalStateException("failed to discover peers: " + reasonCodeToString(reason)));
                        }
                    }
                };

                mManager.discoverPeers(mP2pChannel, discoveryListener);

            } catch (SecurityException e) {
                subject.onError(e);
            }

            return subject.andThen(awaitPeersChanged(15, TimeUnit.SECONDS));
        }).flatMapCompletable(single -> single);
    }

    public Completable initiateConnection(WifiP2pConfig config) {
        return Single.fromCallable(() -> {
            Log.e(TAG, " mylooper " + (Looper.myLooper() == Looper.getMainLooper()));
            final CompletableSubject subject = CompletableSubject.create();
            final AtomicReference<Integer> connectRetry = new AtomicReference<>(10);
            try {
                final WifiP2pManager.ActionListener connectListener = new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.v(TAG, "connected to wifi direct group! FMEEEEE! AM HAPPY!");
                        subject.onComplete();
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "failed to connect to wifi direct group, am v sad. I cry now: " + reasonCodeToString(reason));
                        if (connectRetry.getAndUpdate(val -> --val) > 0) {
                            mManager.connect(mP2pChannel, config, this);
                        } else {
                            subject.onError(new IllegalStateException("failed to connect to group: " + reasonCodeToString(reason)));
                        }
                    }
                };

                mManager.connect(mP2pChannel, config, connectListener);
                return subject;
            } catch (SecurityException e) {
                return Completable.error(e);
            }
        }).flatMapCompletable(completable -> completable);
    }

    public Single<DeclareHashesPacket> declareHashesUke() {
        Log.v(TAG, "declareHashesUke");
        return getServerSocket()
                .flatMapObservable(sock -> DeclareHashesPacket.parseFrom(sock.getInputStream())
                        .subscribeOn(readScheduler)
                        .toObservable()
                        .mergeWith(datastore.getDeclareHashesPacket()
                                .flatMapCompletable(declareHashesPacket ->
                                declareHashesPacket.writeToStream(sock.getOutputStream())
                                ).subscribeOn(writeScheduler)
                        )
                )
                .firstOrError();
    }

    public Single<DeclareHashesPacket> declareHashesSeme(Socket socket) {
        Log.v(TAG, "declareHashesSeme");
        return Single.just(socket)
                .flatMap(sock -> DeclareHashesPacket.parseFrom(sock.getInputStream())
                        .subscribeOn(readScheduler)
                        .toObservable()
                        .mergeWith(datastore.getDeclareHashesPacket()
                                .flatMapCompletable(declareHashesPacket ->
                        declareHashesPacket.writeToStream(sock.getOutputStream())
                                )).subscribeOn(writeScheduler)
                        .firstOrError()
                );
    }

    public Observable<RoutingMetadataPacket> routingMetadataUke(Flowable<RoutingMetadataPacket> packets) {
        return getServerSocket()
                .flatMapObservable(sock -> routingMetadataSeme(sock, packets));
    }

    public Observable<RoutingMetadataPacket> routingMetadataSeme(Socket socket, Flowable<RoutingMetadataPacket> packets) {
        return Observable.just(socket)
                .flatMap(sock -> RoutingMetadataPacket.parseFrom(sock.getInputStream())
                        .toObservable()
                        .repeat()
                        .subscribeOn(readScheduler)
                        .takeWhile(routingMetadataPacket -> {
                            final boolean end = !routingMetadataPacket.isEmpty();
                            if (!end) {
                                Log.v(TAG, "routingMetadata seme end of stream");
                            }
                            return end;
                        }) //TODO: timeout here
                        .mergeWith(packets.concatMapCompletable(p -> p.writeToStream(sock.getOutputStream()))
                                .subscribeOn(writeScheduler)
                        ));
    }

    public Observable<IdentityPacket> identityPacketUke(Flowable<IdentityPacket> packets) {
        return getServerSocket()
                .flatMapObservable(sock -> identityPacketSeme(sock, packets));
    }

    public Observable<IdentityPacket> identityPacketSeme(Socket socket, Flowable<IdentityPacket> packets) {
        return Single.just(socket)
                .flatMapObservable(sock -> IdentityPacket.parseFrom(sock.getInputStream(), mContext)
                        .toObservable()
                        .repeat()
                        .subscribeOn(readScheduler)
                        .takeWhile(identityPacket -> {
                            final boolean end = !identityPacket.isEnd();
                            if (!end) {
                                Log.v(TAG, "identitypacket seme end of stream");
                            }
                            return end;
                        })
                        .mergeWith(packets.concatMapCompletable(p -> p.writeToStream(sock.getOutputStream())
                                .doOnComplete(() -> Log.v(TAG, "wrote single identity packet"))
                        )
                                .subscribeOn(writeScheduler)
                                .doOnComplete(() -> Log.v(TAG, "identity packets complete"))));
    }

    public Completable awaitPeersChanged(int timeout, TimeUnit unit) {
        return mBroadcastReceiver.observePeers()
                .firstOrError()
                .timeout(timeout, unit)
                .ignoreElement();
    }

    public Single<WifiP2pInfo> awaitConnection(int timeout) {
        return mBroadcastReceiver.observeConnectionInfo()
                .takeUntil(info -> info.groupFormed && !info.isGroupOwner)
                .lastOrError()
                .timeout(timeout, TimeUnit.SECONDS)
                .doOnSuccess(info -> Log.v(TAG, "connect to group returned: " + info.groupOwnerAddress))
                .doOnError(err -> Log.e(TAG, "connect to group failed: " + err))
                .doFinally(() -> {
                    groupConnectInProgress.set(false);
                });

    }

    @Override
    public Completable bootstrapFromUpgrade(BootstrapRequest upgradeRequest) {

        Log.v(TAG, "bootstrapFromUpgrade: " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME)
                + " " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE) + " "
                + upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE));

        if (upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                == BluetoothLEModule.ConnectionRole.ROLE_UKE) {
            return retryDelay(createGroup(
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME),
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE)
            ),10, 1)
                    .andThen(routingMetadataUke(Flowable.just(RoutingMetadataPacket.newBuilder().setEmpty().build())).ignoreElements())
                    .andThen(identityPacketUke(datastore.getTopRandomIdentities(20))
                        .reduce(new ArrayList<IdentityPacket>(), (list, packet) -> {
                            list.add(packet);
                            return list;
                        }).flatMapCompletable(datastore::insertIdentityPacket))
                    .andThen(declareHashesUke()
                            .doOnSuccess(p -> Log.v(TAG, "received declare hashes packet uke"))
                            .flatMapCompletable(declareHashesPacket -> readBlockDataUke()
                                    .mergeWith(
                                            writeBlockDataUke(
                                                    datastore.getTopRandomMessages(32, declareHashesPacket) //TODO: configure this
                                                            .toFlowable(BackpressureStrategy.BUFFER)
                                            ))
                    ))
                    .subscribeOn(operationsScheduler);
        } else if(upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                == BluetoothLEModule.ConnectionRole.ROLE_SEME) {
            return retryDelay(connectToGroup(
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME),
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE),
                    120
            ), 20, 1)
                    .flatMapCompletable(info -> getTcpSocket(info.groupOwnerAddress)
                            .flatMapCompletable(socket ->
                                    routingMetadataSeme(socket, Flowable.just(RoutingMetadataPacket.newBuilder().setEmpty().build()))
                                            .ignoreElements()
                                            .andThen(identityPacketSeme(socket, datastore.getTopRandomIdentities(20))) //TODO: configure count
                                            .reduce(new ArrayList<IdentityPacket>(), (list, packet) -> {
                                                list.add(packet);
                                                return list;
                                            })
                                            .flatMapCompletable(datastore::insertIdentityPacket)
                                            .andThen(declareHashesSeme(socket)
                                                    .doOnSuccess(p -> Log.v(TAG, "received declare hashes packet seme"))
                                                    .flatMapCompletable(declareHashesPacket ->
                                                            writeBlockDataSeme(socket, datastore.getTopRandomMessages(32, declareHashesPacket)
                                                                    .toFlowable(BackpressureStrategy.BUFFER))
                                                                    .mergeWith(readBlockDataSeme(socket)))
                                                            )))
                    .doOnSubscribe(disp -> Log.v(TAG, "subscribed to writeBlockData"));
        } else {
            return Completable.error(new IllegalStateException("invalid role"));
        }
    }


    private <T> Observable<T> retryDelay(Observable<T> observable, int count, int seconds) {
        return observable
                .retryWhen(errors -> errors
                        .zipWith(Observable.range(1,count), (v, i) -> i)
                        .concatMapSingle(error -> Single.timer(seconds, TimeUnit.SECONDS)));
    }

    private Completable retryDelay(Completable completable, int count, int seconds) {
        return completable
                .retryWhen(errors -> errors
                        .zipWith(Flowable.range(1,count), (v, i) -> i)
                        .concatMapSingle(error -> Single.timer(seconds, TimeUnit.SECONDS)));
    }

    private <T> Single<T> retryDelay(Single<T> single, int count, int seconds) {
        return single
                .retryWhen(errors -> errors
                        .zipWith(Flowable.range(1,count), (v, i) -> i)
                        .concatMapSingle(error -> Single.timer(seconds, TimeUnit.SECONDS)));
    }


    private Completable writeBlockDataSeme(
        Socket socket,
        Flowable<BlockDataStream> stream
    ) {
        return stream.flatMapCompletable(blockDataStream ->
                blockDataStream.getHeaderPacket().writeToStream(socket.getOutputStream())
                        .subscribeOn(writeScheduler)
                        .doOnComplete(() -> Log.v(TAG, "wrote headerpacket to client socket"))
                        .andThen(
                                blockDataStream.getSequencePackets()
                                        .doOnNext(packet -> Log.v(TAG, "seme writing sequence packet: " + packet.getmData().size()))
                                        .concatMapCompletable(sequencePacket -> sequencePacket.writeToStream(socket.getOutputStream())
                                        )
                                        .subscribeOn(writeScheduler)
                                        .doOnComplete(() -> Log.v(TAG, "wrote sequence packets to client socket"))
                        ));
    }


    private Completable writeBlockDataUke(
        Flowable<BlockDataStream> stream
    ) {
        return getServerSocket()
                .flatMapCompletable(socket ->
                        stream.doOnSubscribe(disp -> Log.v(TAG, "subscribed to BlockDataStream observable"))
                                .doOnNext(p -> Log.v(TAG, "writeBlockData processing BlockDataStream"))
                                .concatMapCompletable(blockDataStream ->
                                        blockDataStream.getHeaderPacket().writeToStream(socket.getOutputStream())
                                                .subscribeOn(writeScheduler)
                                                .doOnComplete(() -> Log.v(TAG, "server wrote header packet"))
                                                .andThen(blockDataStream.getSequencePackets()
                                                        .doOnNext(packet -> Log.v(TAG, "uke writing sequence packet: " + packet.getmData().size()))
                                                        .concatMapCompletable(blockSequencePacket ->
                                                                blockSequencePacket.writeToStream(socket.getOutputStream())
                                                        )
                                                        .subscribeOn(writeScheduler)
                                                        .doOnComplete(() -> Log.v(TAG, "server wrote sequence packets"))
                                                )));
    }

    private Completable readBlockDataUke() {
        return getServerSocket()
                        .toFlowable()
                        .flatMap(socket -> BlockHeaderPacket.parseFrom(socket.getInputStream())
                                .subscribeOn(readScheduler)
                                .toFlowable()
                                .flatMap(headerPacket -> Flowable.range(0, headerPacket.getHashList().size())
                                        .map(i -> new BlockDataStream(
                                        headerPacket,
                                        BlockSequencePacket.parseFrom(socket.getInputStream())
                                                .repeat(headerPacket.getHashList().size())
                                                .subscribeOn(readScheduler)
                                                .doOnNext(packet -> Log.v(TAG, "uke reading sequence packet: " + packet.getmData().size()))
                                                .doOnComplete(() -> Log.v(TAG, "server read sequence packets"))
                                ))
                        ))
                .takeUntil(stream -> {
                    final boolean end = stream.getHeaderPacket().isEndOfStream();
                    if (end) {
                        Log.v(TAG, "uke end of stream");
                    }
                    return end;
                }) //TODO: timeout here
                .concatMapCompletable(m -> datastore.insertMessage(m).andThen(m.await()));
    }

    private Single<Socket> getServerSocket() {
        return socketFactory.create(SCATTERBRAIN_PORT)
                .flatMapObservable(InterceptableServerSocket::observeConnections)
                .map(InterceptableServerSocket.SocketConnection::getSocket)
                .firstOrError()
                .doOnSuccess(n -> Log.v(TAG, "accepted server socket"));
    }

    private Completable readBlockDataSeme(
            Socket socket
    ) {
        return Single.fromCallable(() -> BlockHeaderPacket.parseFrom(socket.getInputStream()).subscribeOn(readScheduler))
                .flatMap(obs -> obs)
                .toFlowable()
                .flatMap(header -> Flowable.range(0, header.getHashList().size())
                        .map(i -> new BlockDataStream(
                                header,
                                BlockSequencePacket.parseFrom(socket.getInputStream())
                                        .repeat(header.getHashList().size())
                                        .subscribeOn(readScheduler)
                                        .doOnNext(packet -> Log.v(TAG, "seme reading sequence packet: " + packet.getmData().size()))
                                        .doOnComplete(() -> Log.v(TAG, "seme complete read sequence packets"))
                        )))
                .takeUntil(stream -> {
                    final boolean end = stream.getHeaderPacket().isEndOfStream();
                    if (end) {
                        Log.v(TAG, "seme end of stream");
                    }
                    return end;
                })
                .concatMapCompletable(m -> datastore.insertMessage(m).andThen(m.await()));
    }
}
