package com.example.uscatterbrain.eventbus;

public interface ScatterEventBusResponse {
    enum StatusCode {
        SEB_OK,SEB_ERROR
    }
    StatusCode getStatusCode();
    ScatterEventBusEvent getInReplyTo();
}
