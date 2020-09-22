package com.example.uscatterbrain;


import android.content.Context;

import androidx.room.Room;

import com.example.uscatterbrain.API.ScatterRoutingService;
import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.db.ScatterbrainDatastoreImpl;
import com.example.uscatterbrain.network.ScatterRadioModule;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl;
import com.example.uscatterbrain.scheduler.ScatterbrainScheduler;
import com.example.uscatterbrain.scheduler.ScatterbrainSchedulerImpl;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;

@Singleton
@Component(modules = RoutingServiceComponent.RoutingServiceModule.class)
public interface RoutingServiceComponent {

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

        @Binds
        abstract ScatterbrainScheduler bindScatterbrainScheduler(ScatterbrainSchedulerImpl impl);

        @Binds
        abstract ScatterRoutingService bindScatterRoutingService(ScatterRoutingServiceImpl impl);

        @Binds
        abstract ScatterbrainDatastore bindDatastore(ScatterbrainDatastoreImpl impl);

        @Binds
        @Named(NamedRadioModules.BLUETOOTH_LE)
        abstract ScatterRadioModule bindRadioModule(BluetoothLERadioModuleImpl impl);
    }

    ScatterRoutingServiceImpl scatterRoutingService();
}
