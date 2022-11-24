package net.ballmerlabs.uscatterbrain

interface ScatterbrainTransactionFactory {

    fun transaction(): ScatterbrainTransactionSubcomponent
}