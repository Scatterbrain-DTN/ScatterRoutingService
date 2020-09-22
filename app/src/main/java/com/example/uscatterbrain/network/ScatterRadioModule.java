package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterRoutingServiceImpl;

import java.util.List;
import java.util.UUID;

public interface ScatterRadioModule {
    UUID register(ScatterRoutingServiceImpl service);
    List<UUID> getPeers();
    UUID getModuleID();
    boolean isRegistered();
}
