package net.ballmerlabs.uscatterbrain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.LifecycleService;

import com.jakewharton.rxrelay2.BehaviorRelay;

import net.ballmerlabs.scatterbrainsdk.Identity;
import net.ballmerlabs.scatterbrainsdk.ScatterMessage;
import net.ballmerlabs.scatterbrainsdk.ScatterbrainAPI;
import net.ballmerlabs.uscatterbrain.db.ApiScatterMessage;
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity;
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Observable;
import io.reactivex.Single;

import static android.os.Binder.getCallingUid;

public class ScatterRoutingService extends LifecycleService {
    public static final int PROTO_VERSION = 0;
    public final String TAG = "ScatterRoutingService";
    private final IBinder mBinder = new ScatterBinder();
    private RoutingServiceBackend mBackend;
    private static final BehaviorRelay<RoutingServiceComponent> component = BehaviorRelay.create();
    private static final String NOTIFICATION_CHANNEL_FOREGROUND = "foreground";
    public static final String PERMISSION_DENIED_STR = "permission denied";
    private final AtomicReference<Boolean> bound = new AtomicReference<>(false);


    private final ScatterbrainAPI.Stub binder = new ScatterbrainAPI.Stub() {

        private boolean checkPermission(String permName) {
            PackageManager pm = getApplicationContext().getPackageManager();
            return PackageManager.PERMISSION_GRANTED == pm.checkPermission(permName, getCallingPackageName());
        }

        private synchronized String getCallingPackageName() {
            // permission check
            String packageName = null;
            String[] packages = getPackageManager().getPackagesForUid(getCallingUid());
            if (packages != null && packages.length > 0) {
                packageName = packages[0];
            }
            if (packageName == null) {
                return "";
            }

            return packageName;
        }

        @RequiresApi(api = Build.VERSION_CODES.P)
        private synchronized void verifyCallingSig(ScatterbrainDatastore.ACL acl) throws RemoteException {
            try {
                final String name = getCallingPackageName();
                if (!name.equals(acl.packageName)) {
                    throw new RemoteException("invalid packagename, access denied");
                }

                PackageInfo info = getPackageManager().getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
                boolean failed = true;
                for (Signature signature : info.signingInfo.getSigningCertificateHistory()) {
                    final String sigtoverify = signature.toCharsString();
                    if (sigtoverify.equals(acl.appsig)) {
                        failed = false;
                    }
                }

                if (failed) {
                    throw new RemoteException("invalid signature, access denied");
                }
            } catch (PackageManager.NameNotFoundException e) {
                throw new RemoteException("invalid package name");
            }
        }

        private void checkAccessPermission() throws RemoteException {
            if (checkPermission(getString(R.string.permission_superuser))) {
                return;
            }
            if (!checkPermission(getString(R.string.permission_access))) {
                throw new RemoteException(PERMISSION_DENIED_STR);
            }
        }

        private void checkAdminPermission() throws RemoteException {
            if (checkPermission(getString(R.string.permission_superuser))) {
                return;
            }
            if (!checkPermission(getString(R.string.permission_admin))) {
                throw new RemoteException(PERMISSION_DENIED_STR);
            }
        }

        private void checkSuperuserPermission() throws RemoteException {
            if (!checkPermission(getString(R.string.permission_superuser))) {
                throw new RemoteException(PERMISSION_DENIED_STR);
            }
        }

        @Override
        public ScatterMessage getById(long id) throws RemoteException {
            checkAccessPermission();
            return mBackend.getDatastore().getApiMessages(id);
        }

        @Override
        public List<ScatterMessage> getByApplication(String application) throws RemoteException {
            checkAccessPermission();
            return mBackend.getDatastore().getApiMessages(application);
        }

        @Override
        public List<Identity> getIdentities() throws RemoteException {
            checkAccessPermission();
                return mBackend.getDatastore().getAllIdentities();
        }

        @Override
        public Identity getIdentityByFingerprint(String fingerprint) throws RemoteException {
            checkAccessPermission();
            return mBackend.getDatastore().getApiIdentityByFingerprint(fingerprint);
        }

        @Override
        public void sendMessage(ScatterMessage message) throws RemoteException {
            checkAccessPermission();
            mBackend.getDatastore().insertAndHashFileFromApi(ApiScatterMessage.fromApi(message), ScatterbrainDatastore.DEFAULT_BLOCKSIZE)
                    .blockingAwait();
        }

        @Override
        public void sendMessages(List<ScatterMessage> messages) throws RemoteException {
            checkAccessPermission();
            Observable.fromIterable(messages)
                    .flatMapCompletable(m -> mBackend.getDatastore().insertAndHashFileFromApi(
                            ApiScatterMessage.fromApi(m),
                            ScatterbrainDatastore.DEFAULT_BLOCKSIZE)
                    )
                    .blockingAwait();
        }


        @RequiresApi(api = Build.VERSION_CODES.P)
        @Override
        public void sendAndSignMessage(ScatterMessage message, String identity) throws RemoteException {
            final List<ScatterbrainDatastore.ACL> acls = mBackend.getDatastore().getACLs(identity).blockingGet();
            for (ScatterbrainDatastore.ACL acl : acls) {
                verifyCallingSig(acl);
            }
            mBackend.getDatastore().getIdentityKey(identity)
                    .flatMapCompletable(id -> mBackend.getDatastore().insertAndHashFileFromApi(
                            ApiScatterMessage.fromApi(message, id),
                            ScatterbrainDatastore.DEFAULT_BLOCKSIZE)).blockingAwait();
        }

        @Override
        public void startDiscovery() throws RemoteException {
            checkAdminPermission();
            mBackend.getScheduler().start();
        }

        @Override
        public void stopDiscovery() throws RemoteException {
            checkAdminPermission();
            mBackend.getScheduler().stop();
        }

        @Override
        public void startPassive() throws RemoteException {
            checkAdminPermission();
            mBackend.getRadioModule().startServer();
        }

        @Override
        public void stopPassive() throws RemoteException {
            checkAdminPermission();
            mBackend.getRadioModule().stopServer();
        }

        @Override
        public Identity generateIdentity(String name) throws RemoteException {
            checkAdminPermission();
            ApiIdentity identity = ApiIdentity.newBuilder()
                    .setName(name)
                    .sign(ApiIdentity.newPrivateKey())
                    .build();
            return mBackend.getDatastore().insertApiIdentity(identity)
                    .toSingleDefault(identity)
                    .blockingGet();
        }

        @RequiresApi(api = Build.VERSION_CODES.P)
        @Override
        public void authorizeApp(String identity, String packagename) throws RemoteException {
            checkSuperuserPermission();
            try {
                PackageInfo info = getPackageManager().getPackageInfo(packagename, PackageManager.GET_SIGNING_CERTIFICATES);
                for (Signature signature : info.signingInfo.getSigningCertificateHistory()) {
                    final String sig = signature.toCharsString();
                    mBackend.getDatastore().addACLs(identity, packagename, sig).blockingAwait();
                }
            } catch (PackageManager.NameNotFoundException e) {
                throw new RemoteException("invalid package name");
            }

        }

        @RequiresApi(api = Build.VERSION_CODES.P)
        @Override
        public void deauthorizeApp(String identity, String packagename) throws RemoteException {
            checkSuperuserPermission();
            try {
                PackageInfo info = getPackageManager().getPackageInfo(packagename, PackageManager.GET_SIGNING_CERTIFICATES);
                for (Signature signature : info.signingInfo.getSigningCertificateHistory()) {
                    final String sig = signature.toCharsString();
                    mBackend.getDatastore().deleteACLs(identity, packagename, sig).blockingAwait();
                }

            } catch (PackageManager.NameNotFoundException e) {
                throw new RemoteException("invalid package name");
            }
        }

        @Override
        public boolean isDiscovering() throws RemoteException {
            checkAccessPermission();
            return mBackend.getScheduler().isDiscovering();
        }

        @Override
        public boolean isPassive() throws RemoteException {
            checkAccessPermission();
            return mBackend.getScheduler().isPassive();
        }
    };

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

            mBackend.getWifiDirect().registerReceiver();
        } catch (Exception e) {
            e.printStackTrace();
            Log.v(TAG, "exception");
        }
    }

    @Override
    public IBinder onBind(Intent i) {
        super.onBind(i);
        return binder;
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
        mBackend.getRadioModule().stopDiscover();
        mBackend.getScheduler().stop();
        mBackend.getWifiDirect().unregisterReceiver();
    }

    public class ScatterBinder extends Binder {
        public ScatterRoutingService getService() {
            return ScatterRoutingService.this;
        }
    }
}
