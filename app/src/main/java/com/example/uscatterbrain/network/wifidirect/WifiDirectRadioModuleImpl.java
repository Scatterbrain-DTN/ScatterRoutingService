package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.github.davidmoten.rx2.IO;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import javax.inject.Inject;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.CompletableSubject;
import io.reactivex.subjects.PublishSubject;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiDirectRadioModuleImpl implements  WifiDirectRadioModule {
    private static final String TAG = "WifiDirectRadioModule";
    private final WifiP2pManager mManager;
    private final WifiDirectBroadcastReceiver mBroadcastReceiver;
    private final WifiP2pManager.Channel mP2pChannel;
    private static final int SCATTERBRAIN_PORT = 7575;
    private static final int CREATE_GROUP_RETRY = 10;
    private static final AtomicReference<Boolean> groupOperationInProgress = new AtomicReference<>();
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
    private static final WifiP2pConfig globalconfig = new WifiP2pConfig.Builder()
            .setNetworkName(GROUP_NAME)
            .setPassphrase(GROUP_PASSPHRASE)
            .build();
    private final Flowable<BlockDataStream> tcpServerOutput;
    private InterceptableServerSocket serverSocket;
    private final Callable<ServerSocket> serverSocketFactory = new Callable<ServerSocket>() {
        @Override
        public ServerSocket call() throws Exception {
            if (serverSocket == null) {
                serverSocket = new InterceptableServerSocket(SCATTERBRAIN_PORT);
            }
            return serverSocket;
        }
    };
    private static final CompositeDisposable wifidirectDisposable = new CompositeDisposable();
    private static final CompositeDisposable tcpServerDisposable = new CompositeDisposable();

    @Inject
    public WifiDirectRadioModuleImpl(
            WifiP2pManager manager,
            WifiDirectBroadcastReceiver receiver,
            WifiP2pManager.Channel channel
    ) {
        this.mManager = manager;
        this.mBroadcastReceiver = receiver;
        this.mP2pChannel = channel;
        this.tcpServerOutput = startTcpServer();
        tcpServerOutput.safeSubscribe(new Subscriber<BlockDataStream>() {
            @Override
            public void onSubscribe(Subscription s) {
                Log.v(TAG, "tcp server initialized");
            }

            @Override
            public void onNext(BlockDataStream blockDataStream) {
                Log.v(TAG, "tcp server accepted headerpacket");
            }

            @Override
            public void onError(Throwable t) {
                Log.e(TAG, "tcp server error: " + t);
            }

            @Override
            public void onComplete() {
                Log.w(TAG, "tcp server completed");
            }
        });
        groupOperationInProgress.set(false);
        Disposable tcpDisposable = this.tcpServerOutput.subscribe(blockDataStream -> {
            Log.v(TAG, "received blockdata stream");
        }, err -> {
            Log.v(TAG, "error while receiving blockdata stream");
        });
        tcpServerDisposable.add(tcpDisposable);

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


    private Flowable<BlockDataStream> startTcpServer() {
        return IO.serverSocket(serverSocketFactory)
                .create()
                .flatMapSingle(connection -> BlockHeaderPacket.parseFrom(connection)
                .map(headerPacket -> new BlockDataStream(
                        headerPacket,
                        BlockSequencePacket.parseFrom(connection)
                        .repeat(headerPacket.getHashList().size()))));
    }

    @Override
    public Completable createGroup() {
        return createGroup(GROUP_NAME, GROUP_PASSPHRASE);
    }

    public Completable createGroup(String name, String passphrase) {
        final WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(name)
                .setPassphrase(passphrase)
                .build();
        final CompletableSubject subject = CompletableSubject.create();
        if ( ! groupOperationInProgress.getAndUpdate(val -> true)) {
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
                                subject.onError(new IllegalStateException("failed to create group: error"));                            groupOperationInProgress.set(false);
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
                .doFinally(() -> groupOperationInProgress.set(false));
    }

    private static Single<Socket> getTcpSocket(InetAddress address) {
        return Single.fromCallable(() -> new Socket(address, SCATTERBRAIN_PORT));
    }

    @Override
    public void connectToGroup() {
        mManager.connect(mP2pChannel, globalconfig, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.v(TAG, "connected to wifi direct group! FMEEEEE! AM HAPPY!");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "failed to connect to wifi direct group, am v sad. I cry now.");
            }
        });
    }

    @Override
    public Observable<BlockDataStream> bootstrapFromUpgrade(
            BluetoothLEModule.UpgradeRequest upgradeRequest,
            Observable<BlockDataStream> streamObservable
            ) {
       return Observable.merge(
               readBlockData(upgradeRequest),
               writeBlockData(upgradeRequest, streamObservable).toObservable()
       );
    }


    private Completable writeBlockData(BluetoothLEModule.UpgradeRequest request, Observable<BlockDataStream> stream) {
        Map<String, String> metadata = request.getPacket().getMetadata();
        if (request.getRole() == BluetoothLEModule.ConnectionRole.ROLE_UKE) {
            if (metadata.containsKey(KEY_GROUP_NAME) && metadata.containsKey(KEY_GROUP_PASSPHRASE)) {
                return createGroup(metadata.get(KEY_GROUP_NAME), metadata.get(KEY_GROUP_PASSPHRASE))
                        .andThen(
                                stream
                                        .flatMapCompletable(blockDataStream -> Observable.fromIterable(serverSocket.getSockets())
                                                .flatMapCompletable(socket -> blockDataStream.getHeaderPacket()
                                                        .writeToStream(socket.getOutputStream())
                                                        .andThen(blockDataStream.getSequencePackets()
                                                                .concatMapCompletable(sequencePacket ->
                                                                        sequencePacket.writeToStream(socket.getOutputStream()))
                                                        )))
                );
            } else {
                return Completable.error(new IllegalStateException("invalid metadata"));
            }
        } else if (request.getRole() == BluetoothLEModule.ConnectionRole.ROLE_SEME) {
            return mBroadcastReceiver.observeConnectionInfo()
                    .flatMapSingle(info -> {
                        if (info.groupFormed && !info.isGroupOwner) {
                            return getTcpSocket(info.groupOwnerAddress);
                        } else {
                            return Single.never();
                        }
                    })
                    .flatMapCompletable(socket -> stream.flatMapCompletable(blockDataStream ->
                            blockDataStream.getHeaderPacket().writeToStream(socket.getOutputStream())
                            .andThen(
                                    blockDataStream.getSequencePackets()
                                    .concatMapCompletable(sequencePacket -> sequencePacket.writeToStream(socket.getOutputStream())
                            ))));
        } else {
            return Completable.error(new IllegalStateException("invalid role"));
        }
    }

    private Observable<BlockDataStream> readBlockData(BluetoothLEModule.UpgradeRequest upgradeRequest) {
        Map<String, String> metadata = upgradeRequest.getPacket().getMetadata();
        if (upgradeRequest.getRole() == BluetoothLEModule.ConnectionRole.ROLE_UKE) {
            if (metadata.containsKey(KEY_GROUP_NAME) && metadata.containsKey(KEY_GROUP_PASSPHRASE)) {
                return createGroup(metadata.get(KEY_GROUP_NAME), metadata.get(KEY_GROUP_PASSPHRASE))
                        .andThen(tcpServerOutput)
                        .toObservable();
            } else {
                return Observable.error(new IllegalStateException("invalid metadata"));
            }
        } else if (upgradeRequest.getRole() == BluetoothLEModule.ConnectionRole.ROLE_SEME) {
            return mBroadcastReceiver.observeConnectionInfo()
                    .flatMapSingle(info -> {
                        if (info.groupFormed && !info.isGroupOwner) {
                            return getTcpSocket(info.groupOwnerAddress);
                        } else {
                            return Single.never();
                        }
                    })
                    .flatMapSingle(socket -> BlockHeaderPacket.parseFrom(socket.getInputStream())
                            .map(header -> new BlockDataStream(
                                    header,
                                    BlockSequencePacket.parseFrom(socket.getInputStream())
                                            .repeat(header.getHashList().size())
                            )));
        } else {
            return Observable.error(new IllegalStateException("invalid role"));
        }
    }
}
