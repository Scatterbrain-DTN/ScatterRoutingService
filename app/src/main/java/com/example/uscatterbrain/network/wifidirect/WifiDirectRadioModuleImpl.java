package com.example.uscatterbrain.network.wifidirect;

import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.uscatterbrain.RoutingServiceComponent;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.BlockSequencePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.github.davidmoten.rx2.IO;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Named;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiDirectRadioModuleImpl implements  WifiDirectRadioModule {
    private static final String TAG = "WifiDirectRadioModule";
    private final WifiP2pManager mManager;
    private final WifiDirectBroadcastReceiver mBroadcastReceiver;
    private final WifiP2pManager.Channel mP2pChannel;
    private final Scheduler readScheduler;
    private final Scheduler writeScheduler;
    private static final int SCATTERBRAIN_PORT = 7575;
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
            WifiP2pManager.Channel channel,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFIDIRECT_READ) Scheduler readScheduler,
            @Named(RoutingServiceComponent.NamedSchedulers.WIFIDIRECT_WRITE) Scheduler writeScheduler
    ) {
        this.readScheduler = readScheduler;
        this.writeScheduler = writeScheduler;
        this.mManager = manager;
        this.mBroadcastReceiver = receiver;
        this.mP2pChannel = channel;
        this.tcpServerOutput = startTcpServer();
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
        mManager.createGroup(mP2pChannel, globalconfig, actionListener);
        return mBroadcastReceiver.observeConnectionInfo()
                .takeUntil(wifiP2pInfo -> wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner)
                .ignoreElements();
    }

    public Completable createGroup(String name, String passphrase) {
        final WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(name)
                .setPassphrase(passphrase)
                .build();
        mManager.requestGroupInfo(mP2pChannel, group -> {
            if (group == null) {
                mManager.createGroup(mP2pChannel, config, actionListener);
            }
        });
        return mBroadcastReceiver.observeConnectionInfo()
                .takeUntil(wifiP2pInfo -> wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner)
                .ignoreElements();
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
