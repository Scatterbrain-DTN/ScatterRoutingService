package net.ballmerlabs.uscatterbrain.network.desktop

import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.KeyExchange
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface.sodium
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import net.ballmerlabs.uscatterbrain.scheduler.DesktopSession

data class PublicKeyPair(
    val pubkey: ByteArray,
    val privkey: ByteArray
) {

    fun fingerprint(): ByteArray {
        val out = ByteArray(GenericHash.BLAKE2B_BYTES_MIN)
        if (LibsodiumInterface.sodium.crypto_generichash(out, out.size, pubkey, pubkey.size.toLong(), null, 0) != 0 ) {
            throw IllegalStateException("failed to hash")
        }

        return out
    }



    fun session(remote: ByteArray, db: DesktopClient): DesktopSessionConfig {
        if (remote.size != KeyExchange.PUBLICKEYBYTES) {
            throw IllegalStateException("remote key wrong bytes")
        }
        val tx = ByteArray(KeyExchange.SESSIONKEYBYTES)
        val rx = ByteArray(KeyExchange.SESSIONKEYBYTES)
        if (sodium.crypto_kx_server_session_keys(
                rx,
                tx,
                pubkey,
                privkey,
                remote
            ) != 0) {
            throw SecurityException("failed to generate session keys")
        }

        return DesktopSessionConfig(
            rx = rx,
            tx = tx,
            kx = this,
            fingerprint = LibsodiumInterface.fingerprint(remote),
            remotepub = remote,
            db = db
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PublicKeyPair

        if (!pubkey.contentEquals(other.pubkey)) return false
        if (!privkey.contentEquals(other.privkey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pubkey.contentHashCode()
        result = 31 * result + privkey.contentHashCode()
        return result
    }

    companion object {
        fun create(): PublicKeyPair {
            val kp = PublicKeyPair(
                pubkey = ByteArray(Box.PUBLICKEYBYTES),
                privkey = ByteArray(Box.SECRETKEYBYTES)
            )

            if (sodium.crypto_kx_keypair(kp.pubkey, kp.privkey) != 0) {
                throw SecurityException("failed to generate key")
            }
            return kp
        }
    }
}