package net.ballmerlabs.uscatterbrain.network

import com.goterl.lazycode.lazysodium.LazySodiumAndroid
import com.goterl.lazycode.lazysodium.Sodium
import com.goterl.lazycode.lazysodium.SodiumAndroid
import com.goterl.lazycode.lazysodium.interfaces.Hash
import com.goterl.lazycode.lazysodium.interfaces.Sign
import java.util.*

/**
 * Singleton interface to libsodium/lazysodium over JNA
 */
object LibsodiumInterface {
    private var mSodiumInstance: LazySodiumAndroid? = null
    const val SODIUM_BASE64_VARIANT_ORIGINAL_NO_PADDING = 1
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

    val signNative: Sign.Native?
        get() {
            checkSodium()
            return mSodiumInstance
        }

    val signLazy: Sign.Lazy?
        get() {
            checkSodium()
            return mSodiumInstance
        }

    val hashNative: Hash.Native?
        get() {
            checkSodium()
            return mSodiumInstance
        }

    val hashLazy: Hash.Lazy?
        get() {
            checkSodium()
            return mSodiumInstance
        }

    fun base64enc(data: ByteArray?): String {
        return Base64.getEncoder().encodeToString(data)
    }

    fun base64dec(data: String?): ByteArray {
        return Base64.getDecoder().decode(data)
    }
}