package net.ballmerlabs.uscatterbrain.network

import com.goterl.lazycode.lazysodium.LazySodiumAndroid
import com.goterl.lazycode.lazysodium.Sodium
import com.goterl.lazycode.lazysodium.SodiumAndroid
import java.util.*

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

    fun base64enc(data: ByteArray?): String {
        return Base64.getEncoder().encodeToString(data)
    }

    fun base64dec(data: String?): ByteArray {
        return Base64.getDecoder().decode(data)
    }
}