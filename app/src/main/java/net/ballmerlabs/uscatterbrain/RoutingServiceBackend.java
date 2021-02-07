package net.ballmerlabs.uscatterbrain;

import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler;

public interface RoutingServiceBackend {
    class Applications {
        public static final String APPLICATION_FILESHARING = "fileshare";
    }
    AdvertisePacket getPacket();
    BluetoothLEModule getRadioModule();
    WifiDirectRadioModule getWifiDirect();
    ScatterbrainDatastore getDatastore();
    ScatterbrainScheduler getScheduler();
}
