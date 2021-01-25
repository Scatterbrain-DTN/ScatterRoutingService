package com.example.uscatterbrain.API;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.network.IdentityPacket;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

public class Identity implements Parcelable {
    private Map<String, byte[]> mPubKeymap;
    private final byte[] mScatterbrainPubKey;
    private final String givenname;
    private final AtomicReference<ByteString> sig = new AtomicReference<>(ByteString.EMPTY);

    private int validatePubkey(int pubkey) {
        if (pubkey != Sign.PUBLICKEYBYTES) {
            throw new BadParcelableException("invalid pubkey size");
        }
        return pubkey;
    }

    @FunctionalInterface
    private interface ParcelWriter<T> {
        void writeToParcel(@NonNull final T value,
                           @NonNull final Parcel parcel, final int flags);
    }

    @FunctionalInterface
    private interface ParcelReader<T> {
        T readFromParcel(@NonNull final Parcel parcel);
    }

    private static <K, V> void writeParcelableMap(
            @NonNull final Map<K, V> map,
            @NonNull final Parcel parcel,
            final int flags,
            @NonNull final ParcelWriter<Map.Entry<K, V>> parcelWriter) {
        parcel.writeInt(map.size());

        for (final Map.Entry<K, V> e : map.entrySet()) {
            parcelWriter.writeToParcel(e, parcel, flags);
        }
    }

    private static <K, V> Map<K, V> readParcelableMap(
            @NonNull final Parcel parcel,
            @NonNull final ParcelReader<Map.Entry<K, V>> parcelReader) {
        int size = parcel.readInt();
        final Map<K, V> map = new HashMap<>(size);

        for (int i = 0; i < size; i++) {
            final Map.Entry<K, V> value = parcelReader.readFromParcel(parcel);
            map.put(value.getKey(), value.getValue());
        }
        return map;
    }

    private Identity(Builder builder) {
        this.mScatterbrainPubKey = builder.mPubKeymap.get(IdentityPacket.PROTOBUF_PRIVKEY_KEY);
        this.givenname = builder.name;
    }

    protected Identity(Parcel in) {
        mPubKeymap = readParcelableMap(in, parcel -> {
            final int len = validatePubkey(parcel.readInt());
            final byte[] key = new byte[len];
            parcel.readByteArray(key);
            return new AbstractMap.SimpleEntry<>(parcel.readString(), key);
        });
        mScatterbrainPubKey = mPubKeymap.get(IdentityPacket.PROTOBUF_PRIVKEY_KEY);
        givenname = in.readString();
    }

    public static final Creator<Identity> CREATOR = new Creator<Identity>() {
        @Override
        public Identity createFromParcel(Parcel in) {
            return new Identity(in);
        }

        @Override
        public Identity[] newArray(int size) {
            return new Identity[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        writeParcelableMap(mPubKeymap, parcel, i, (mapentry, p, __) -> {
            p.writeInt(mapentry.getValue().length);
            p.writeByteArray(mapentry.getValue());
            p.writeString(mapentry.getKey());
        });
        parcel.writeString(givenname);
    }

    public ByteString sumBytes() {
        ByteString result = ByteString.EMPTY;
        result = result.concat(ByteString.copyFromUtf8(givenname));
        SortedSet<String> sortedKeys= new TreeSet<>(mPubKeymap.keySet());
        for (String key : sortedKeys) {
            result = result.concat(ByteString.copyFromUtf8(key));
            ByteString val = ByteString.copyFrom(mPubKeymap.get(key));
            if (val == null) {
                return null;
            }
            result = result.concat(val);
        }
        return result;
    }


    /**
     * Sign ed 25519 boolean.
     *
     * @param secretkey the secretkey
     * @return the boolean
     */
    public synchronized boolean signEd25519(byte[] secretkey) {
        if (secretkey.length != Sign.SECRETKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();

        byte[] sig = new byte[Sign.ED25519_BYTES];
        Pointer p = new PointerByReference(Pointer.NULL).getPointer();
        if (LibsodiumInterface.getSodium().crypto_sign_detached(sig,
                p, messagebytes.toByteArray(), messagebytes.size(), secretkey) == 0) {
            this.sig.set(ByteString.copyFrom(sig));
            return true;
        } else {
            return false;
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, byte[]> mPubKeymap = new HashMap<>();
        private String name;
        private byte[] pubkey;
        private byte[] privkey;

        private Builder() {
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder sign(byte[] pubkey, byte[] privkey) {
            this.privkey = privkey;
            this.pubkey = pubkey;
            return this;
        }

        public Builder addKeys(Map<String, byte[]> keys) {
            this.mPubKeymap.putAll(keys);
            return this;
        }

        public Identity build() {
            if (name == null) {
                throw new IllegalArgumentException("name should be non-null");
            }

            if (pubkey == null || privkey == null) {
                throw new IllegalArgumentException("signature required");
            }

            mPubKeymap.put(IdentityPacket.PROTOBUF_PRIVKEY_KEY, pubkey);
            final Identity identity = new Identity(this);
            identity.signEd25519(privkey);
            return identity;
        }
    }
}
