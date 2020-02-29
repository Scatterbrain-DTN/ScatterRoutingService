package com.example.uscatterbrain;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import android.util.Log;

import com.example.uscatterbrain.API.HighLevelAPI;
import com.example.uscatterbrain.API.OnRecieveCallback;
import com.example.uscatterbrain.API.ScatterTransport;
import com.example.uscatterbrain.db.Datastore;
import com.example.uscatterbrain.db.ScatterbrainDatastore;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BlockDataPacket;
import com.example.uscatterbrain.network.bluetoothLE.ScatterBluetoothLEManager;

import java.io.InputStream;

public class ScatterRoutingService extends LifecycleService implements HighLevelAPI {
    public final String TAG = "ScatterRoutingService";
    private boolean bound;
    private final IBinder mBinder = new ScatterBinder();
    private ScatterBluetoothLEManager leManager;
    private DeviceProfile myprofile;

    public ScatterRoutingService() {

    }

    @Override
    public void stopService() {
    }

    @Override
    public SharedPreferences getPref() {
        return null;
    }

    @Override
    public void setPref(SharedPreferences pref) {

    }

    @Override
    public DeviceProfile getProfile() {
        return myprofile;
    }

    @Override
    public void setProfile(DeviceProfile prof) {
        this.myprofile = prof;
    }

    @Override
    public ScatterTransport[] getTransports() {
        return null;
    }

    @Override
    public String getScatterApplication() {
        return null;
    }

    @Override
    public void setScatterApplication(String application) {

    }

    //peers
    @Override
    public void scanOn(ScatterTransport transport) {
        leManager.startScan();
    }

    @Override
    public void scanOff(ScatterTransport transport) {
        leManager.stopScan();
    }

    @Override
    public void advertiseOn() {
        if(myprofile != null) {
            AdvertisePacket ad = new AdvertisePacket(myprofile);
            leManager.startLEAdvertise(ad.getBytes());
        } else {
            Log.e(TAG, "err: tried to start advertise with null profile");
        }
    }

    @Override
    public void advertiseOff() {
        leManager.stopLEAdvertise();
    }

    @Override
    public DeviceProfile[] getPeers() {
        return null;
    }

    //communications
    @Override
    public boolean sendDataDirected(DeviceProfile target, byte[] data) {
        return false;
    }

    @Override
    public void sendDataMulticast(byte[] data) {

    }

    @Override
    public boolean sendFileDirected(DeviceProfile target, InputStream file, String name, long len) {
        return false;
    }

    @Override
    public void sendFileMulticast(InputStream file, String name, long len) {

    }

    @Override
    public void registerOnRecieveCallback(OnRecieveCallback callback) {

    }

    //datastore
    @Override
    public BlockDataPacket[] getTopMessages(int num) {
        return null;
    }

    @Override
    public BlockDataPacket[] getRandomMessages(int num) {
        return null;
    }

    //datastore systems tasks

    @Override
    public void flushDatastore() {

    }

    @Override
    public void setDatastoreLimit(int limit) {

    }

    @Override
    public int getDatastoreLimit() {
        return 0;
    }

    @Override
    public void startService() {
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
            man.notify(notificationId, not.build());
        } catch(NullPointerException e) {
            Log.e(TAG, "NullPointerException while creating persistent notification");
        }
        leManager = new ScatterBluetoothLEManager(getApplicationContext());

    }

    @Override
    public IBinder onBind(Intent i) {
        super.onBind(i);
        bound = true;
        return mBinder;
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
        ScatterbrainDatastore.initialize(this);
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
