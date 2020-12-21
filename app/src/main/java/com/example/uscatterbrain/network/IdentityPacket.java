package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

/**
 * Wrapper class for protocol buffer upgrade message
 */
public class IdentityPacket implements ScatterSerializable {

    private final ScatterProto.Identity mIdentity;
    private final String mGivenName;
    private final Map<String, ByteString> mKeys;
    private final ByteString mSig;
    private UUID luidtag;

    private IdentityPacket(Builder builder) {
        this.mGivenName = builder.getName();
        this.mKeys = builder.getKeys();
        this.mSig = builder.getSig();
        this.mIdentity = ScatterProto.Identity.newBuilder()
                .setGivenname(mGivenName)
                .setSig(mSig)
                .putAllKeys(mKeys)
                .build();
    }

    private IdentityPacket(InputStream is) throws IOException {
        this.mIdentity = ScatterProto.Identity.parseDelimitedFrom(is);
        this.mGivenName = mIdentity.getGivenname();
        this.mKeys = mIdentity.getKeysMap();
        this.mSig = mIdentity.getSig();
    }

    /**
     * Parse from identity packet.
     *
     * @param is the is
     * @return the identity packet
     */
    public static Single<IdentityPacket> parseFrom(InputStream is) {
        return Single.fromCallable(() -> new IdentityPacket(is));
    }

    public static Single<IdentityPacket> parseFrom(Observable<byte[]> flowable) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return IdentityPacket.parseFrom(observer).doFinally(observer::close);
    }

    public static Single<IdentityPacket> parseFrom(Flowable<byte[]> flowable) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return IdentityPacket.parseFrom(observer).doFinally(observer::close);
    }

    private ByteString sumBytes() {
        ByteString result = ByteString.EMPTY;
        ArrayList<String> strings = new ArrayList<>(mKeys.keySet());
        strings.sort(Comparator.naturalOrder());
        result = result.concat(ByteString.copyFromUtf8(mGivenName));
        for (String key : strings) {
            result = result.concat(ByteString.copyFromUtf8(key));
        }
        return  result;
    }

    /**
     * Verify ed25519 signature of this packet
     *
     * @param pubkey the pubkey
     * @return the boolean
     */
    public boolean verifyed25519(byte[] pubkey) {
        if (pubkey.length != Sign.PUBLICKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();
        return LibsodiumInterface.getSodium().crypto_sign_verify_detached(this.mSig.toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size(),
                pubkey) == 0;
    }

    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            mIdentity.writeDelimitedTo(os);
            return os.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ByteString getByteString() {
        return ByteString.copyFrom(getBytes());
    }

    @Override
    public Completable writeToStream(OutputStream os) {
        return Completable.fromAction(() -> mIdentity.writeDelimitedTo(os));
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mIdentity;
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_IDENTITY;
    }

    @Override
    public void tagLuid(UUID luid) {
        luidtag = luid;
    }

    @Override
    public UUID getLuid() {
        return luidtag;
    }

    /**
     * Gets name.
     *
     * @return the name
     */
    public String getName() {
        return mGivenName;
    }

    /**
     * Gets keys.
     *
     * @return the keys
     */
    public Map<String, ByteString> getKeys() {
        return mKeys;
    }

    /**
     * Gets sig.
     *
     * @return the sig
     */
    public ByteString getSig() {
        return mSig;
    }

    /**
     * New builder builder.
     *
     * @return the builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * The type Builder.
     */
    public static class Builder {
        private String mGivenName;
        private Map<String, ByteString> mKeys;
        private byte[] mPrivateKey;
        private ByteString mSig;

        /**
         * Instantiates a new Builder.
         */
        public Builder() {

        }

        /**
         * Sets name.
         *
         * @param name the name
         * @return the name
         */
        public Builder setName(String name) {
            this.mGivenName = name;
            return this;
        }

        /**
         * Sets keys.
         *
         * @param keys the keys
         * @return the keys
         */
        public Builder setKeys(Map<String, ByteString> keys) {
            this.mKeys = keys;
            return this;
        }

        /**
         * Sets ed25519 private key used for signing this packet
         *
         * @param key the key
         * @return the sign key
         */
        public Builder setSignKey(byte[] key) {
            this.mPrivateKey = key;
            return this;
        }


        private ByteString sumBytes() {
            ByteString result = ByteString.EMPTY;
            ArrayList<String> strings = new ArrayList<>(mKeys.keySet());
            strings.sort(Comparator.naturalOrder());
            result = result.concat(ByteString.copyFromUtf8(mGivenName));
            for (String key : strings) {
                result = result.concat(ByteString.copyFromUtf8(key));
            }
            return  result;
        }

        private boolean sign() {
            if (mPrivateKey.length != Sign.SECRETKEYBYTES)
                return false;

            ByteString messageBytes = sumBytes();
            byte[] sig = new byte[Sign.ED25519_BYTES];
            Pointer p = new PointerByReference(Pointer.NULL).getPointer();

            if (LibsodiumInterface.getSodium().crypto_sign_detached(sig, p, messageBytes.toByteArray(),
                    messageBytes.size(), mPrivateKey) != 0) {
                return false;
            }

            this.mSig = ByteString.copyFrom(sig);
            return true;
        }

        /**
         * Gets name.
         *
         * @return the name
         */
        public String getName() {
            return mGivenName;
        }

        /**
         * Gets keys.
         *
         * @return the keys
         */
        public Map<String, ByteString> getKeys() {
            return mKeys;
        }

        /**
         * Gets sig.
         *
         * @return the sig
         */
        public ByteString getSig() {
            return mSig;
        }

        /**
         * Build identity packet.
         *
         * @return the identity packet
         */
        public IdentityPacket build() {
            if (mGivenName == null || mPrivateKey == null || mKeys == null) {
                return null;
            }

            if (!sign()) {
                return null;
            }

            return new IdentityPacket(this);
        }

    }
}
