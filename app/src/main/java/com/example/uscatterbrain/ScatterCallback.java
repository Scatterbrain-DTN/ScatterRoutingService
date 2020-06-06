package com.example.uscatterbrain;

public interface ScatterCallback<T,R> {
    R call(T object);
}
