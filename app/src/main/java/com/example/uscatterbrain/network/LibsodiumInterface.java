package com.example.uscatterbrain.network;

import com.goterl.lazycode.lazysodium.LazySodiumAndroid;
import com.goterl.lazycode.lazysodium.Sodium;
import com.goterl.lazycode.lazysodium.SodiumAndroid;
import com.goterl.lazycode.lazysodium.interfaces.Hash;
import com.goterl.lazycode.lazysodium.interfaces.Sign;

/**
 * Singleton interface to libsodium/lazysodium over JNA
 */
public class LibsodiumInterface {
    private static LazySodiumAndroid mSodiumInstance = null;

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
}
