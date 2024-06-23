package net.ballmerlabs.uscatterbrain.network.desktop

import io.reactivex.Single

interface DesktopKeyManager {
    fun getKeypair(): Single<PublicKeyPair>
}