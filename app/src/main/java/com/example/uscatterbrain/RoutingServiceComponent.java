package com.example.uscatterbrain;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;

import androidx.room.Room;

import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.ScatterbrainDatastoreImpl;
import com.example.uscatterbrain.db.file.FileStore;
import com.example.uscatterbrain.db.file.FileStoreImpl;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl;
import com.example.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver;
import com.example.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiverImpl;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl;
import com.example.uscatterbrain.network.wifidirect.WifiDirectUnregisteredReceiver;
import com.example.uscatterbrain.scheduler.ScatterbrainScheduler;
import com.example.uscatterbrain.scheduler.ScatterbrainSchedulerImpl;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleServer;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import io.reactivex.Scheduler;
import io.reactivex.plugins.RxJavaPlugins;

@Singleton
@Component(modules = RoutingServiceComponent.RoutingServiceModule.class)
public interface RoutingServiceComponent {

    class NamedSchedulers {
        public static final String DATABASE = "executor_database";
        public static final String BLE = "scheduler-ble";
        public static final String WIFI_DIRECT_READ = "wifi-direct-read";
        public static final String WIFI_DIRECT_WRITE = "wifi-direct-write";
        public static final String WIFI_DIRECT_OPERATIONS = "wifi-direct-operations";
        private NamedSchedulers() {

        }
    }

    class NamedRadioModules {
        public static final String BLUETOOTH_LE = "bluetooth-le";
    }

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder applicationContext(Context context);

        RoutingServiceComponent build();
    }

    @Module
    abstract class RoutingServiceModule {

        @Singleton
        @Provides
        static Datastore provideDatastore(Context ctx) {
            return Room.databaseBuilder(ctx, Datastore.class, ScatterbrainDatastore.DATABASE_NAME)
                    .build();
        }

        @Provides
        @Singleton
        static WifiDirectBroadcastReceiver providesWifiP2pIntentFilter(Context ctx, WifiDirectUnregisteredReceiver receiver) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);

            // Indicates a change in the list of available peers.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);

            // Indicates the state of Wi-Fi P2P connectivity has changed.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

            // Indicates this device's details have changed.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

            ctx.registerReceiver(receiver.asReceiver(), intentFilter);

            return receiver.asPublic();
        }

        @Provides
        static WifiP2pManager providesWifiP2pManager(Context ctx) {
            return (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        }

        @Provides
        static WifiP2pManager.Channel providesWifiP2pChannel(WifiP2pManager manager, Context ctx) {
            return manager.initialize(ctx, ctx.getMainLooper(), null);
        }

        @Provides
        @Named(NamedSchedulers.DATABASE)
        static Scheduler provideDatabaseScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        @Named(NamedSchedulers.WIFI_DIRECT_READ)
        static Scheduler provideWifiDirectReadScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        @Named(NamedSchedulers.WIFI_DIRECT_WRITE)
        static Scheduler provideWifiDirectWriteScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        @Named(NamedSchedulers.WIFI_DIRECT_OPERATIONS)
        static Scheduler provideWifiDirectOperationsScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        static RxBleClient provideRxBleClient(Context ctx) {
            return RxBleClient.create(ctx);
        }

        @Provides
        static RxBleServer providesRxBleServer(Context context) {
            return RxBleServer.create(context);
        }

        @Provides
        @Named(NamedSchedulers.BLE)
        static Scheduler provideBleScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        static BluetoothLeAdvertiser provideLeAdvertiser() {
            return BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        }

        @Binds
        @Singleton
        abstract RoutingServiceBackend bindsRoutingServiceBackend(RoutingServiceBackendImpl impl);

        @Binds
        abstract ScatterbrainScheduler bindScatterbrainScheduler(ScatterbrainSchedulerImpl impl);

        @Binds
        abstract ScatterbrainDatastore bindDatastore(ScatterbrainDatastoreImpl impl);

        @Binds
        abstract WifiDirectRadioModule bindWifiDirectRadioModule(WifiDirectRadioModuleImpl impl);

        @Binds
        @Singleton
        abstract WifiDirectUnregisteredReceiver bindWifiDirectBroadcastReceiver(WifiDirectBroadcastReceiverImpl impl);

        @Binds
        abstract FileStore bindFileStore(FileStoreImpl impl);

        @Binds
        abstract BluetoothLEModule bindRadioModuleInternal(BluetoothLERadioModuleImpl impl);
    }

    RoutingServiceBackend scatterRoutingService();
}
