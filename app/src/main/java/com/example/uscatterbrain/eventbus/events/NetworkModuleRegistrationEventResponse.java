package com.example.uscatterbrain.eventbus.events;

import com.example.uscatterbrain.eventbus.ScatterEventBusEvent;
import com.example.uscatterbrain.eventbus.ScatterEventBusResponse;

public class NetworkModuleRegistrationEventResponse implements ScatterEventBusResponse {
    private StatusCode mStatusCode;
    private ScatterEventBusEvent mEvent;

    public StatusCode getStatusCode() {
        return mStatusCode;
    }

    public ScatterEventBusEvent getInReplyTo() {
        return mEvent;
    }
}
