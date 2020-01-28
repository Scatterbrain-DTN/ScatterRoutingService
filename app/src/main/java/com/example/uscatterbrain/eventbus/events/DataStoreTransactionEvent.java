package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;

public class DataStoreTransactionEvent implements ScatterEventBusEvent {
    private String mComponentID;

    //TODO: fill out when datastore is finished.

    public DataStoreTransactionEvent(DataStoreTransactionEventBuilder builder) {
        this.mComponentID = builder.mComponentID;
    }

    public String getComponentID() {
        return mComponentID;
    }

    public static class DataStoreTransactionEventBuilder {
        private String mComponentID;

        public DataStoreTransactionEventBuilder(String componentID) {
            this.mComponentID = componentID;
        }

        public DataStoreTransactionEvent build() {
            DataStoreTransactionEvent event = new DataStoreTransactionEvent(this);
            return event;
        }
    }
}
