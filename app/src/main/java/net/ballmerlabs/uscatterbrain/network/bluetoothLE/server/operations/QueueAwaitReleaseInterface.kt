package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

interface QueueAwaitReleaseInterface {
    @Throws(InterruptedException::class)
    fun awaitRelease()
}