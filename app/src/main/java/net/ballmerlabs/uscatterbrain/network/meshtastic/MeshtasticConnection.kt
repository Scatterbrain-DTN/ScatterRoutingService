package net.ballmerlabs.uscatterbrain.network.meshtastic

interface MeshtasticConnection {
    fun subscribeReceiver()
    fun unsubscribeReceiver()
}