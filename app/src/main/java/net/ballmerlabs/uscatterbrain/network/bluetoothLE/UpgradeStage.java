package net.ballmerlabs.uscatterbrain.network.bluetoothLE;

import android.util.Base64;
import android.util.Log;

import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface;
import net.ballmerlabs.uscatterbrain.network.UpgradePacket;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import io.reactivex.Single;

public class UpgradeStage {
    public static final String TAG = "UpgradeStage";
    private final AdvertisePacket.Provides provides;
    private final Map<String, String> metadata = new HashMap<>();
    private final int sessionID = new Random(System.nanoTime()).nextInt();

    public UpgradeStage(AdvertisePacket.Provides provides) {
        this.provides = provides;
        initMetadata();
    }


    private void initMetadata() {
        switch (provides) {
            case WIFIP2P:
            {
                metadata.putIfAbsent(WifiDirectBootstrapRequest.KEY_NAME, WifiDirectBootstrapRequest.DEFAULT_NAME);
                byte[] pass = new byte[16];
                LibsodiumInterface.getSodium().randombytes_buf(pass, pass.length);
                metadata.putIfAbsent(
                        WifiDirectBootstrapRequest.KEY_PASSPHRASE,
                        Base64.encodeToString(pass, Base64.NO_WRAP | Base64.NO_PADDING)
                );
                break;
            }
            default:
            {
                Log.e(TAG, "initMetadata called with invalid provides");
            }
        }
    }


    public Single<UpgradePacket> getUpgrade() {
        switch (provides) {
            case WIFIP2P:
            {
                return Single.fromCallable(() -> {
                  return UpgradePacket.newBuilder()
                          .setProvides(provides)
                          .setMetadata(metadata)
                          .setSessionID(sessionID)
                          .build();
                });
            }
            default:
                return Single.error(new IllegalStateException("unsupported provides"));
        }
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public int getSessionID() {
        return sessionID;
    }
}
