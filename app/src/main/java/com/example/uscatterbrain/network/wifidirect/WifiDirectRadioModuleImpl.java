package com.example.uscatterbrain.network.wifidirect;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.uscatterbrain.RoutingServiceComponent;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;

import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.CompletableSubject;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiDirectRadioModuleImpl implements WifiDirectRadioModule {
    private final WifiP2pManager mManager;
    private final WifiDirectBroadcastReceiver mBroadcastReceiver;
    private final WifiP2pManager.Channel mP2pChannel;
    private final Scheduler readScheduler;
    private final Scheduler writeScheduler;
    private final Scheduler operationsScheduler;
    private final Context mContext;
    private static final int SCATTERBRAIN_PORT = 7575;
    private static final int CREATE_GROUP_RETRY = 10;
    private static final InterceptableServerSocket.InterceptableServerSocketFactory socketFactory =
            new InterceptableServerSocket.InterceptableServerSocketFactory();
    private static final AtomicReference<Boolean> groupOperationInProgress = new AtomicReference<>();
    private static final AtomicReference<Boolean> groupConnectInProgress = new AtomicReference<>();
    private static final WifiP2pManager.ActionListener actionListener = new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
            Log.v(TAG, "created wifi direct group");
        }

        @Override
        public void onFailure(int reason) {
            Log.e(TAG, "failed to create wifi direct group");
        }
    };

    private static final CompositeDisposable wifidirectDisposable = new CompositeDisposable();
    private static final CompositeDisposable tcpServerDisposable = new CompositeDisposable();

    @Inject
    public WifiDirectRadioModuleImpl(
            WifiP2pManager manager,
            WifiDirectBroadcastReceiver receiver,
            WifiP2pManager.Channel channel,
            Context context,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_READ) Scheduler readScheduler,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_WRITE) Scheduler writeScheduler,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_OPERATIONS) Scheduler operationsScheduler
    ) {
        this.mContext = context;
        this.mManager = manager;
        this.mBroadcastReceiver = receiver;
        this.mP2pChannel = channel;
        this.readScheduler = readScheduler;
        this.writeScheduler = writeScheduler;
        this.operationsScheduler = operationsScheduler;
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
    }

    public Completable createGroup(String name, String passphrase) {
        Log.v(TAG, "creategroup called" + name + " " + passphrase);
        try {
            final WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setNetworkName(name)
                    .setPassphrase(passphrase)
                    .build();
            final CompletableSubject subject = CompletableSubject.create();
            if (!groupOperationInProgress.getAndUpdate(val -> true)) {
                final AtomicReference<Integer> retryCount = new AtomicReference<>();
                retryCount.set(CREATE_GROUP_RETRY);
                final WifiP2pManager.ActionListener listener = new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.v(TAG, "successfully created group!");
                        subject.onComplete();
                        groupOperationInProgress.set(false);
                    }

                    @Override
                    public void onFailure(int reason) {
                        switch (reason) {
                            case WifiP2pManager.BUSY: {
                                Log.w(TAG, "failed to create group: busy: retry");
                                if (retryCount.getAndUpdate(integer -> --integer) > 0) {
                                    mManager.createGroup(mP2pChannel, this);
                                } else {
                                    subject.onError(new IllegalStateException("failed to create group: busy retry exceeded"));
                                    groupOperationInProgress.set(false);
                                }
                                break;
                            }
                            case WifiP2pManager.ERROR: {
                                Log.e(TAG, "failed to create group: error");
                                if (retryCount.getAndUpdate(integer -> --integer) > 0) {
                                    mManager.createGroup(mP2pChannel, this);
                                } else {
                                    subject.onError(new IllegalStateException("failed to create group: error"));
                                    groupOperationInProgress.set(false);
                                    groupOperationInProgress.set(false);
                                }
                                break;
                            }
                            case WifiP2pManager.P2P_UNSUPPORTED: {
                                Log.e(TAG, "failed to create group: p2p unsupported");
                                subject.onError(new IllegalStateException("failed to create group: p2p unsupported"));
                                groupOperationInProgress.set(false);
                                break;
                            }
                            default: {
                                subject.onError(new IllegalStateException("invalid status code"));
                                groupOperationInProgress.set(false);
                                break;
                            }
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
                                switch (reason) {
                                    case WifiP2pManager.BUSY: {
                                        Log.w(TAG, "failed to remove old group: busy: retry");
                                        if (retryCount.getAndUpdate(integer -> --integer) > 0) {
                                            mManager.removeGroup(mP2pChannel, this);
                                        } else {
                                            groupOperationInProgress.set(false);
                                        }
                                        break;
                                    }
                                    case WifiP2pManager.ERROR: {
                                        Log.w(TAG, "failed to remove group probably nonexistent, retry");
                                        if (retryCount.getAndUpdate(integer -> --integer) > 0) {
                                            mManager.removeGroup(mP2pChannel, this);
                                        } else {
                                            subject.onError(new IllegalStateException("failed to remove group: error"));
                                            groupOperationInProgress.set(false);
                                        }
                                        break;
                                    }
                                    case WifiP2pManager.P2P_UNSUPPORTED: {
                                        Log.e(TAG, "failed to remove group: p2p unsupported");
                                        subject.onError(new IllegalStateException("failed to create group: p2p unsupported"));
                                        groupOperationInProgress.set(false);
                                        break;
                                    }
                                    default: {
                                        subject.onError(new IllegalStateException("invalid status code"));
                                        groupOperationInProgress.set(false);
                                        break;
                                    }
                                }
                            }
                        });
                    }
                });
            } else {
                subject.onComplete();
            }
            return subject.andThen(mBroadcastReceiver.observeConnectionInfo()
                    .takeUntil(wifiP2pInfo -> wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner)
                    .ignoreElements().timeout(10, TimeUnit.SECONDS))
                    .doOnComplete(() -> Log.v(TAG, "createGroup return success"))
                    .doFinally(() -> groupOperationInProgress.set(false));
        } catch (SecurityException e) {
            return Completable.error(e);
        }
    }

    private static Single<Socket> getTcpSocket(InetAddress address) {
        return Single.fromCallable(() -> new Socket(address, SCATTERBRAIN_PORT));
    }

    public static final String reasonCodeToString(int reason) {
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
    public Single<WifiP2pInfo> connectToGroup(String name, String passphrase) {
        if (!groupConnectInProgress.getAndUpdate(val -> true)) {
            WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setPassphrase(passphrase)
                    .setNetworkName(name)
                    .build();
            return initateConnection(config)
                    .retry(10)
                    .andThen(awaitConnection());
        } else {
            return awaitConnection();
        }
    }

    public Completable discoverPeers() {
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
                        subject.onError(new IllegalStateException("failed to discver peers: " + reasonCodeToString(reason)));
                    }
                }
            };

            mManager.discoverPeers(mP2pChannel, discoveryListener);

        } catch (SecurityException e) {
            subject.onError(e);
        }

        return subject.andThen(awaitPeersChanged(15, TimeUnit.SECONDS));
    }

    public Completable initateConnection(WifiP2pConfig config) {
        final CompletableSubject subject = CompletableSubject.create();
        final AtomicReference<Integer> connectretry = new AtomicReference<>(20);
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
                    if (connectretry.getAndUpdate(val -> --val) > 0) {
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
    }

    public Completable awaitPeersChanged(int timeout, TimeUnit unit) {
        return mBroadcastReceiver.observePeers()
                .firstOrError()
                .timeout(timeout, unit)
                .ignoreElement();
    }

    public Single<WifiP2pInfo> awaitConnection() {
        return mBroadcastReceiver.observeConnectionInfo()
                .takeUntil(info -> info.groupFormed && !info.isGroupOwner)
                .lastOrError()
                .timeout(20, TimeUnit.SECONDS)
                .doOnSuccess(info -> Log.v(TAG, "connect to group returned: " + info.groupOwnerAddress))
                .doOnError(err -> Log.e(TAG, "connect to group failed: " + err))
                .doFinally(() -> {
                    groupConnectInProgress.set(false);
                });

    }

    @Override
    public Observable<BlockDataStream> bootstrapFromUpgrade(
            WifiDirectBootstrapRequest upgradeRequest,
            Observable<BlockDataStream> streamObservable
            ) {

        Log.v(TAG, "bootstrapFromUpgrade: " + upgradeRequest.getName() + " " + upgradeRequest.getPassphrase() +
                " " + upgradeRequest.getRole());
        Disposable tcpserverdisposable = socketFactory.create(SCATTERBRAIN_PORT)
                .flatMapObservable(InterceptableServerSocket::acceptLoop)
                .subscribeOn(operationsScheduler)
                .subscribe(
                        socket -> Log.v(TAG,"accepted socket: " + socket.getSocket()),
                        err -> Log.e(TAG, "error when accepting socket: " + err)
                );
        return Observable.merge(
                   readBlockData(upgradeRequest)
                .doOnError(err -> {
                    Log.e(TAG, "error on readBlockData: " + err);
                    err.printStackTrace();
                }),
                   writeBlockData(upgradeRequest, streamObservable).toObservable())
                .doOnError(err -> {
                    Log.e(TAG, "error on writeBlockData" + err);
                    err.printStackTrace();
                }
           ).retryWhen(errors -> errors
                        .zipWith(Observable.range(1,20), (v, i) -> i)
                        .flatMapSingle(error -> Single.timer(10, TimeUnit.SECONDS)))
                    .doFinally(tcpserverdisposable::dispose);
    }


    private Completable writeBlockData(
            WifiDirectBootstrapRequest request,
            Observable<BlockDataStream> stream
    ) {
        if (request.getRole() == BluetoothLEModule.ConnectionRole.ROLE_UKE) {
            return createGroup(request.getName(), request.getPassphrase())
                    .andThen(socketFactory.create(SCATTERBRAIN_PORT))
                    .flatMapObservable(InterceptableServerSocket::observeConnections)
                    .map(InterceptableServerSocket.SocketConnection::getSocket)
                    .flatMapCompletable(socket ->
                            stream.flatMapCompletable(blockDataStream ->
                                    blockDataStream.getHeaderPacket().writeToStream(socket.getOutputStream())
                                            .subscribeOn(writeScheduler)
                                            .doOnComplete(() -> Log.v(TAG, "server wrote header packet"))
                                            .andThen(blockDataStream.getSequencePackets()
                                                    .concatMapCompletable(blockSequencePacket ->
                                                            blockSequencePacket.writeToStream(socket.getOutputStream())
                                                                    .subscribeOn(writeScheduler))
                                                    .doOnComplete(() -> Log.v(TAG, "server wrote sequence packets"))
                                            )));
        } else if (request.getRole() == BluetoothLEModule.ConnectionRole.ROLE_SEME) {
            return connectToGroup(request.getName(), request.getPassphrase())
                    .flatMap(info -> getTcpSocket(info.groupOwnerAddress))
                    .flatMapCompletable(socket -> stream.flatMapCompletable(blockDataStream ->
                            blockDataStream.getHeaderPacket().writeToStream(socket.getOutputStream())
                                    .subscribeOn(writeScheduler)
                                    .doOnComplete(() -> Log.v(TAG, "wrote headerpacket to client socket"))
                                    .andThen(
                                            blockDataStream.getSequencePackets()
                                                    .concatMapCompletable(sequencePacket -> sequencePacket.writeToStream(socket.getOutputStream())
                                                            .subscribeOn(writeScheduler)
                                                    ).doOnComplete(() -> Log.v(TAG, "wrote sequence packets to client socket"))
                                    )));
        } else {
            return Completable.error(new IllegalStateException("invalid role"));
        }
    }

    private Observable<BlockDataStream> readBlockData(
            WifiDirectBootstrapRequest upgradeRequest
    ) {
        if (upgradeRequest.getRole() == BluetoothLEModule.ConnectionRole.ROLE_UKE) {
            return createGroup(upgradeRequest.getName(), upgradeRequest.getPassphrase())
                    .andThen(socketFactory.create(SCATTERBRAIN_PORT))
                    .flatMapObservable(serverSocket -> serverSocket.observeConnections()
                            .map(InterceptableServerSocket.SocketConnection::getSocket)
                            .flatMapSingle(socket -> BlockHeaderPacket.parseFrom(socket.getInputStream())
                                    .subscribeOn(readScheduler)
                                    .doOnSuccess(packet -> Log.v(TAG, "server read header packet"))
                                    .map(headerPacket -> new BlockDataStream(
                                            headerPacket,
                                            BlockSequencePacket.parseFrom(socket.getInputStream())
                                                    .subscribeOn(readScheduler)
                                                    .repeat(headerPacket.getHashList().size())
                                                    .doOnComplete(() -> Log.v(TAG, "server read sequence packets"))
                                    ))));
        } else if (upgradeRequest.getRole() == BluetoothLEModule.ConnectionRole.ROLE_SEME) {
            return connectToGroup(upgradeRequest.getName(), upgradeRequest.getPassphrase())
                    .flatMap(info -> getTcpSocket(info.groupOwnerAddress))
                    .flatMap(socket -> BlockHeaderPacket.parseFrom(socket.getInputStream())
                            .subscribeOn(readScheduler)
                            .doOnSuccess(packet -> Log.v(TAG, "client read header packet"))
                            .map(header -> new BlockDataStream(
                                    header,
                                    BlockSequencePacket.parseFrom(socket.getInputStream())
                                            .subscribeOn(readScheduler)
                                            .repeat(header.getHashList().size())
                                            .doOnComplete(() -> Log.v(TAG, "client read sequence packets"))
                            ))).toObservable();
        } else {
            return Observable.error(new IllegalStateException("invalid role"));
        }
    }
}
