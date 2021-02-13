package net.ballmerlabs.uscatterbrain.scheduler;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import net.ballmerlabs.uscatterbrain.API.HandshakeResult;
import net.ballmerlabs.uscatterbrain.API.ScatterbrainApi;
import net.ballmerlabs.uscatterbrain.R;
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.disposables.Disposable;

@Singleton
public class ScatterbrainSchedulerImpl implements ScatterbrainScheduler {
    static final String TAG = "Scheduler";
    private final AtomicReference<RoutingServiceState>  mState;
    private final BluetoothLEModule bluetoothLEModule;
    private final WifiDirectRadioModule wifiDirectRadioModule;
    private final ScatterbrainDatastore datastore;
    private final Context context;
    private boolean isDiscovering = false;
    private boolean isAdvertising = false;
    private final AtomicReference<Disposable> globalDisposable = new AtomicReference<>();

    @Inject
    public ScatterbrainSchedulerImpl(
            WifiDirectRadioModule wifiDirectRadioModule,
            BluetoothLEModule bluetoothLEModule,
            ScatterbrainDatastore scatterbrainDatastore,
            Context context
    ) {
        this.context = context;
        this.mState = new AtomicReference<>(RoutingServiceState.STATE_SUSPEND);
        this.wifiDirectRadioModule = wifiDirectRadioModule;
        this.bluetoothLEModule = bluetoothLEModule;
        this.datastore = scatterbrainDatastore;
        Disposable d = this.bluetoothLEModule.observeTransactions()
                .subscribe(
                        this::broadcastTransactionResult,
                        err -> Log.e(TAG, "fatal error, transaction relay somehow called onError")
                );
    }

    private void broadcastTransactionResult(final HandshakeResult transactionStats) {
        final Intent intent = new Intent(context.getString(R.string.broadcast_message));
        intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, transactionStats);
        context.sendBroadcast(intent, context.getString(R.string.permission_access));
    }

    @Override
    public RoutingServiceState getRoutingServiceState() {
        return this.mState.get();
    }

    @Override
    public synchronized void start() {
        if (isAdvertising) {
            return;
        }
        isAdvertising = true;
        bluetoothLEModule.startAdvertise();
        bluetoothLEModule.startServer();
        final Disposable d = bluetoothLEModule.discoverForever()
                .doOnSubscribe(disp -> isDiscovering = true)
                .doOnDispose(() -> isDiscovering = false)
                .subscribe(
                        res -> Log.v(TAG, "finished transaction: " + res),
                        err -> Log.e(TAG, "error in transaction: " + err)
                );

        globalDisposable.getAndUpdate(disp -> {
            if (disp != null) {
                disp.dispose();
            }
            return d;
        });

    }

    @Override
    public synchronized boolean stop() {
        if (!isAdvertising) {
            return false;
        }
        isAdvertising = false;
        bluetoothLEModule.stopAdvertise();
        bluetoothLEModule.stopServer();
        globalDisposable.getAndUpdate(disp -> {
            if (disp != null) {
                disp.dispose();
            }
            return null;
        });
        return true;
    }

    @Override
    public boolean isDiscovering() {
        return isDiscovering;
    }

    @Override
    public boolean isPassive() {
        return isAdvertising && !isDiscovering;
    }
}
