package com.example.uscatterbrain;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;

import androidx.room.Room;

import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.ScatterbrainDatastoreImpl;
import com.example.uscatterbrain.db.file.FileStore;
import com.example.uscatterbrain.db.file.FileStoreImpl;
import com.example.uscatterbrain.network.BlockDataSourceFactory;
import com.example.uscatterbrain.network.BlockDataSourceFactoryImpl;
import com.example.uscatterbrain.network.BluetoothLEModuleInternal;
import com.example.uscatterbrain.network.ScatterRadioModule;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl;
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
        @Named(NamedSchedulers.DATABASE)
        static Scheduler provideDatabaseScheduler() {
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
        abstract BlockDataSourceFactory bindBlockDataSourceFactory(BlockDataSourceFactoryImpl impl);

        @Binds
        abstract FileStore bindFileStore(FileStoreImpl impl);

        @Binds
        @Singleton
        @Named(NamedRadioModules.BLUETOOTH_LE)
        abstract ScatterRadioModule bindRadioModule(BluetoothLERadioModuleImpl impl);

        @Binds
        abstract BluetoothLEModuleInternal bindRadioModuleInternal(BluetoothLERadioModuleImpl impl);
    }

    RoutingServiceBackend scatterRoutingService();
}
