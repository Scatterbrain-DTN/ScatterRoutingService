package com.example.uscatterbrain.network.wifidirect;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class WifiDirectProviderImpl implements WifiDirectProvider {

    private final WifiDirectRadioModule wifiDirectRadioModule;

    @Inject
    public WifiDirectProviderImpl(
        WifiDirectRadioModule wifiDirectRadioModule

    ) {
        this.wifiDirectRadioModule = wifiDirectRadioModule;
    }

    @Override
    public WifiDirectRadioModule getRadioModule() {
        return wifiDirectRadioModule;
    }
}
