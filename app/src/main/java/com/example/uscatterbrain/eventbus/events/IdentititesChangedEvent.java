package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;

public class IdentititesChangedEvent implements ScatterEventBusEvent {

    private String mComponenetID;
    //TODO: fill out when identity management code is done

    public String getComponentID() {
        return mComponenetID;
    }

    public IdentititesChangedEvent(IdentitiesChangedEventBuilder builder) {
        this.mComponenetID = builder.mComponentID;
    }

    public static class IdentitiesChangedEventBuilder {
        public String mComponentID;

        public IdentitiesChangedEventBuilder(String componentID) {
            this.mComponentID = componentID;
        }

        public IdentititesChangedEvent build() {
            IdentititesChangedEvent event = new IdentititesChangedEvent(this);
            return event;
        }

    }
}
