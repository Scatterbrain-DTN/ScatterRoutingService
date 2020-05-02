package com.example.uscatterbrain.network;

import com.goterl.lazycode.lazysodium.LazySodiumAndroid;
import com.goterl.lazycode.lazysodium.Sodium;
import com.goterl.lazycode.lazysodium.SodiumAndroid;
import com.goterl.lazycode.lazysodium.interfaces.Hash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

import java.util.Base64;

/**
 * Singleton interface to libsodium/lazysodium over JNA
 */
public class LibsodiumInterface {
    private static LazySodiumAndroid mSodiumInstance = null;
    public static final int SODIUM_BASE64_VARIANT_ORIGINAL_NO_PADDING = 1;

    private LibsodiumInterface() {}

    private static void checkSodium() {
        if (mSodiumInstance == null) {
            mSodiumInstance = new LazySodiumAndroid(new SodiumAndroid());
        }
    }

    public static Sodium getSodium() {
        checkSodium();
        return mSodiumInstance.getSodium();
    }

    public static Sign.Native getSignNative() {
        checkSodium();
        return mSodiumInstance;
    }

    public static Sign.Lazy getSignLazy() {
        checkSodium();
        return mSodiumInstance;
    }

    public static Hash.Native getHashNative() {
        checkSodium();
        return mSodiumInstance;
    }

    public static Hash.Lazy getHashLazy() {
        checkSodium();
        return mSodiumInstance;
    }

    public static String base64enc(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] base64dec(String data) {
        return Base64.getDecoder().decode(data);
    }
}
