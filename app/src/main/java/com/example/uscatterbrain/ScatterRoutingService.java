package com.example.uscatterbrain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.example.uscatterbrain.API.OnRecieveCallback;
import com.example.uscatterbrain.API.ScatterTransport;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockHeaderPacket;
import com.example.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.jakewharton.rxrelay2.BehaviorRelay;

import java.io.InputStream;
import java.util.Objects;

import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;

public class ScatterRoutingService extends LifecycleService {
    public final String TAG = "ScatterRoutingService";
    private boolean bound;
    private final IBinder mBinder = new ScatterBinder();
    private DeviceProfile myprofile;
    private RoutingServiceBackend mBackend;
    private static final BehaviorRelay<RoutingServiceComponent> component = BehaviorRelay.create();
    private static final String NOTIFICATION_CHANNEL_FOREGROUND = "foreground";

    public ScatterRoutingService() {

    }

    public static Single<RoutingServiceComponent> getComponent() {
        return component.firstOrError();
    }

    public AdvertisePacket getPacket() {
        return mBackend.getPacket();
    }

    public void setPacket(AdvertisePacket packet) {
        //TODO:
    }


    public void stopService() {
    }


    public SharedPreferences getPref() {
        return null;
    }


    public void setPref(SharedPreferences pref) {

    }


    public DeviceProfile getProfile() {
        return myprofile;
    }


    public void setProfile(DeviceProfile prof) {
        this.myprofile = prof;
    }


    public ScatterTransport[] getTransports() {
        return null;
    }


    public String getScatterApplication() {
        return null;
    }


    public void setScatterApplication(String application) {

    }

    //peers

    public void scanOn(ScatterTransport transport) {

    }


    public void scanOff(ScatterTransport transport) {

    }


    public void advertiseOn() {

    }


    public void advertiseOff() {

    }


    public DeviceProfile[] getPeers() {
        return null;
    }

    //communications

    public boolean sendDataDirected(DeviceProfile target, byte[] data) {
        return false;
    }


    public void sendDataMulticast(byte[] data) {

    }


    public boolean sendFileDirected(DeviceProfile target, InputStream file, String name, long len) {
        return false;
    }


    public void sendFileMulticast(InputStream file, String name, long len) {

    }


    public void registerOnRecieveCallback(OnRecieveCallback callback) {

    }

    //datastore

    public BlockHeaderPacket[] getTopMessages(int num) {
        return null;
    }


    public BlockHeaderPacket[] getRandomMessages(int num) {
        return null;
    }

    //datastore systems tasks


    public void flushDatastore() {

    }


    public void setDatastoreLimit(int limit) {

    }


    public int getDatastoreLimit() {
        return 0;
    }

    public void startService() {

    }

    @Override
    public void onCreate() {
        super.onCreate();
    }


    @Override
    public IBinder onBind(Intent i) {
        try {
            super.onBind(i);
            //TODO: temporary
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
            bound = true;
            Log.v(TAG, "initialized datastore");
            return mBinder;
        } catch (Exception e) {
            e.printStackTrace();
            Log.v(TAG, "exception");
            return null;
        }
    }

    //TODO: remove this in production
    public BluetoothLEModule getRadioModule() {
        return mBackend.getRadioModule();
    }

    //TODO: remove this in production
    public WifiDirectRadioModule getWifiDirect() {
        return mBackend.getWifiDirect();
    }

    @Override
    public boolean onUnbind(Intent i) {
        super.onUnbind(i);
        bound = false;
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
