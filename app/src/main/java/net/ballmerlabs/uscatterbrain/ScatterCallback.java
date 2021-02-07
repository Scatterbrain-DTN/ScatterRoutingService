package net.ballmerlabs.uscatterbrain;

public interface ScatterCallback<T,R> {
    R call(T object);
}
