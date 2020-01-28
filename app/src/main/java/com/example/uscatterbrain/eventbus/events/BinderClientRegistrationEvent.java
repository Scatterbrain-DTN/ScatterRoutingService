package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;

public class BinderClientRegistrationEvent implements ScatterEventBusEvent {
    private String mComponentID;

    //TODO: fill out when multiple binder support is done

    public String getComponentID() {
        return mComponentID;
    }

    public BinderClientRegistrationEvent(BinderClientRegistrationEventBuilder builder) {
        this.mComponentID = builder.mComponentID;
    }

    public static class BinderClientRegistrationEventBuilder {
        private String mComponentID;

        public BinderClientRegistrationEventBuilder(String mComponentID) {
            this.mComponentID = mComponentID;
        }

        public BinderClientRegistrationEvent build() {
            BinderClientRegistrationEvent event = new BinderClientRegistrationEvent(this);
            return event;
        }

    }
}
