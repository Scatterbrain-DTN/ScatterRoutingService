package com.example.uscatterbrain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleService;

import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.jakewharton.rxrelay2.BehaviorRelay;

import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Single;

public class ScatterRoutingService extends LifecycleService {
    public final String TAG = "ScatterRoutingService";
    private final IBinder mBinder = new ScatterBinder();
    private RoutingServiceBackend mBackend;
    private static final BehaviorRelay<RoutingServiceComponent> component = BehaviorRelay.create();
    private static final String NOTIFICATION_CHANNEL_FOREGROUND = "foreground";
    private final AtomicReference<Boolean> bound = new AtomicReference<>(false);

    public ScatterRoutingService() {

    }

    public static Single<RoutingServiceComponent> getComponent() {
        return component.firstOrError();
    }

    public AdvertisePacket getPacket() {
        return mBackend.getPacket();
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onStart(@Nullable Intent intent, int startId) {
        super.onStart(intent, startId);
        try {
            RoutingServiceComponent c = DaggerRoutingServiceComponent.builder()
                    .applicationContext(this)
                    .build();

            component.accept(c);

            mBackend = c.scatterRoutingService();

            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_FOREGROUND,
                    "fmef",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
            Intent notificationIntent = new Intent(this, ScatterRoutingService.class);
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, notificationIntent, 0);

            Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_FOREGROUND)
                    .setContentTitle("ScatterRoutingService")
                    .setContentText("discovering peers...")
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setContentIntent(pendingIntent)
                    .setTicker("fmef am tire")
                    .build();

            startForeground(1, notification);
            Log.v(TAG, "called onbind");
            Log.v(TAG, "initialized datastore");
        } catch (Exception e) {
            e.printStackTrace();
            Log.v(TAG, "exception");
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        super.onBind(i);
        return mBinder;
    }

    //TODO: remove this in production
    public BluetoothLEModule getRadioModule() {
        return mBackend.getRadioModule();
    }

    //TODO: remove this in production
    public WifiDirectRadioModule getWifiDirect() {
        return mBackend.getWifiDirect();
    }

    //TODO: remove this in production
    public ScatterbrainDatastore getDatastore() {
        return mBackend.getDatastore();
    }

    @Override
    public boolean onUnbind(Intent i) {
        super.onUnbind(i);
        return true;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public class ScatterBinder extends Binder {
        public ScatterRoutingService getService() {
            return ScatterRoutingService.this;
        }
    }

}
