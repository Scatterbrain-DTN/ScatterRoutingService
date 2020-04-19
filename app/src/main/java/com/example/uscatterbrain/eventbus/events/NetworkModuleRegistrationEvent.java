package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;

public class NetworkModuleRegistrationEvent implements ScatterEventBusEvent {

    private String mModuleName;
    private String mComponentID;

    public String getmModuleName() {
        return mModuleName;
    }

    public String getComponentID() {return mComponentID; }

    public NetworkModuleRegistrationEvent(NetworkModuleRegistrationEventBuilder builder) {
        this.mComponentID = builder.mComponentID;
        this.mModuleName = builder.mModuleName;
    }

    public static class NetworkModuleRegistrationEventBuilder {
        public String mModuleName;
        public String mComponentID;

        public NetworkModuleRegistrationEventBuilder(String componentID) {
            this.mComponentID = componentID;
        }

        public NetworkModuleRegistrationEventBuilder setModuleName(String moduleName) {
            this.mModuleName = moduleName;
            return this;
        }

        public NetworkModuleRegistrationEvent build() {
            return new NetworkModuleRegistrationEvent(this);
        }
    }
}
