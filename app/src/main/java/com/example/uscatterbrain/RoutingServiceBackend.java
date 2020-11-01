package com.example.uscatterbrain;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.BluetoothLEModuleInternal;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModuleDebug;

public interface RoutingServiceBackend {
    AdvertisePacket getPacket();
    BluetoothLEModuleInternal getRadioModule();
    WifiDirectRadioModuleDebug getWifiDirect();
}
