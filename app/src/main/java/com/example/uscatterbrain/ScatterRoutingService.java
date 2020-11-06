package com.example.uscatterbrain;

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

import java.io.InputStream;
import java.util.Objects;

public class ScatterRoutingService extends LifecycleService {
    public final String TAG = "ScatterRoutingService";
    private boolean bound;
    private final IBinder mBinder = new ScatterBinder();
    private DeviceProfile myprofile;
    private RoutingServiceBackend mBackend;

    public ScatterRoutingService() {

    }

    public AdvertisePacket getPacket() {
        return mBackend.getPacket();
    }

    public void setPacket(AdvertisePacket packet) {
        //TODO:
    }

    public AdvertisePacket getPacket() {
        return mPacket;
    }

    public void setPacket(AdvertisePacket packet) {
        mPacket = packet;
    }

    @Override
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
        super.onBind(i);
        bound = true;
        ScatterbrainDatastore.initialize(this);
        mRadioModule.register(this);
        NotificationCompat.Builder not = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_toys_black_24dp)
                .setContentTitle("Scatterbrain")
                .setContentText("Discoverting Peers...");

        Intent result = new Intent(this, ScatterRoutingService.class);

        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        result,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        not.setContentIntent(resultPendingIntent);

        int notificationId = 1;
        NotificationManager man = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            Objects.requireNonNull(man).notify(notificationId, not.build());
        } catch(NullPointerException e) {
            Log.e(TAG, "NullPointerException while creating persistent notification");
        }
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
