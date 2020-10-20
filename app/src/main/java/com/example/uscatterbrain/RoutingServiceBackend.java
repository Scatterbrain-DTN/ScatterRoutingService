package com.example.uscatterbrain;

import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.ScatterRadioModule;

public interface RoutingServiceBackend {
    AdvertisePacket getPacket();
    ScatterRadioModule getRadioModule();
}
