package net.ballmerlabs.uscatterbrain.network

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.Sodium
import com.goterl.lazysodium.SodiumAndroid

/**
 * Singleton interface to libsodium/lazysodium over JNA
 */
object LibsodiumInterface {
    private var mSodiumInstance: LazySodiumAndroid? = null
    private fun checkSodium() {
        if (mSodiumInstance == null) {
            mSodiumInstance = LazySodiumAndroid(SodiumAndroid())
        }
    }

    val sodium: Sodium
        get() {
            checkSodium()
            return mSodiumInstance!!.sodium
        }

    fun base64enc(data: ByteArray): String {
        return android.util.Base64.encode(data, android.util.Base64.DEFAULT).decodeToString()
    }

    fun base64dec(data: String): ByteArray {
        return android.util.Base64.decode(data, android.util.Base64.DEFAULT)
    }
}