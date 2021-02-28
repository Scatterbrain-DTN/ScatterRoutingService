package net.ballmerlabs.uscatterbrain

interface ScatterCallback<T, R> {
    fun call(`object`: T): R
}