package com.example.uscatterbrain.identity;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.InputStreamFlowableSubscriber;
import com.example.uscatterbrain.network.InputStreamObserver;
import com.example.uscatterbrain.network.LibsodiumInterface;
import com.example.uscatterbrain.network.ScatterSerializable;
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
import java.net.ProtocolException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class Identity implements Map<String, ByteString>, ScatterSerializable {
    private Map<String, ByteString>  mPubKeymap = new TreeMap<>();
    private byte[] mScatterbrainPubKey;
    private byte[] mSig;
    private SharedPreferences mKeystorePrefs;
    private final Context mCtx;
    private final String mGivenName;
    private ScatterProto.Identity mIdentity;
    private static final String PROTOBUF_PRIVKEY_KEY = "scatterbrain";
    private static final String KEYSTORE_ID = "scatterbrainkeystore";

    private Identity(Builder builder) throws GeneralSecurityException, IOException {
        this.mCtx = builder.getContext();
        this.mGivenName = builder.getName();
        this.mSig = builder.getSig();
        ByteString sigbytestring = ByteString.EMPTY;
        if (mSig != null) {
            sigbytestring = ByteString.copyFrom(mSig);
        }
        initKeyStore();
        if (builder.ismGenerateKeypair()) {
            generateKeyPair();
        } else {
            this.mScatterbrainPubKey = builder.getScatterbrainPubkey();
            this.mPubKeymap.put(PROTOBUF_PRIVKEY_KEY, ByteString.copyFrom(mScatterbrainPubKey));
            this.mIdentity = ScatterProto.Identity.newBuilder()
                    .setGivennameBytes(ByteString.copyFromUtf8(mGivenName))
                    .setSig(ByteString.EMPTY)
                    .putAllKeys(mPubKeymap)
                    .setSig(sigbytestring)
                    .build();
        }
    }

    private void initKeyStore() throws GeneralSecurityException, IOException {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        this.mKeystorePrefs = EncryptedSharedPreferences.create(
                KEYSTORE_ID,
                masterKeyAlias,
                this.mCtx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    public byte[] getPrivKey() {
        if (mKeystorePrefs.contains(LibsodiumInterface.base64enc(mScatterbrainPubKey))) {
            String result = mKeystorePrefs.getString(LibsodiumInterface.base64enc(mScatterbrainPubKey), null);
            if (result == null) {
                return null;
            } else {
                return LibsodiumInterface.base64dec(result);
            }
        }
        return null;
    }

    private Identity(InputStream is, Context mCtx) throws IOException, GeneralSecurityException {
        this.mIdentity = ScatterProto.Identity.parseDelimitedFrom(is);
        this.mCtx = mCtx;
        initKeyStore();
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        this.mGivenName = mIdentity.getGivenname();
        this.mPubKeymap = mIdentity.getKeysMap();

        ByteString scatterbrainKey = mPubKeymap.get(PROTOBUF_PRIVKEY_KEY);
        if (scatterbrainKey == null) {
            throw new ProtocolException("scatterbrain key not in map");
        }
        this.mScatterbrainPubKey = scatterbrainKey.toByteArray();
    }


    public static Single<Identity> parseFrom(InputStream is, Context ctx) {
        return Single.fromCallable(() -> new Identity(is, ctx));
    }

    public static Single<Identity> parseFrom(Observable<byte[]> flowable, Context ctx) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return Identity.parseFrom(observer, ctx).doFinally(observer::close);
    }

    public static Single<Identity> parseFrom(Flowable<byte[]> flowable, Context ctx) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return Identity.parseFrom(observer, ctx).doFinally(observer::close);
    }

    public ByteString sumBytes() {
        ByteString result = ByteString.EMPTY;
        result = result.concat(ByteString.copyFromUtf8(mGivenName));
        SortedSet<String> sortedKeys= new TreeSet<>(mPubKeymap.keySet());
        for (String key : sortedKeys) {
            result = result.concat(ByteString.copyFromUtf8(key));
            ByteString val = mPubKeymap.get(key);
            if (val == null) {
                return null;
            }
            result = result.concat(val);
        }
        return result;
    }

    /**
     * Verifyed 25519 boolean.
     *
     * @param pubkey the pubkey
     * @return the boolean
     */
    public boolean verifyed25519(byte[] pubkey) {
        if (pubkey.length != Sign.PUBLICKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();
        return LibsodiumInterface.getSodium().crypto_sign_verify_detached(this.mIdentity.getSig().toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size(),
                pubkey) == 0;
    }

    @Override
    public PacketType getType() {
        return PacketType.TYPE_IDENTITY;
    }

    /**
     * Sign ed 25519 boolean.
     *
     * @param secretkey the secretkey
     * @return the boolean
     */
    public boolean signEd25519(byte[] secretkey) {
        if (secretkey.length != Sign.SECRETKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();

        this.mSig = new byte[Sign.ED25519_BYTES];
        Pointer p = new PointerByReference(Pointer.NULL).getPointer();
        if (LibsodiumInterface.getSodium().crypto_sign_detached(this.mSig,
                p, messagebytes.toByteArray(), messagebytes.size(), secretkey) == 0) {
            this.mIdentity = ScatterProto.Identity.newBuilder()
                    .setGivennameBytes(ByteString.copyFromUtf8(mGivenName))
                    .setSig(ByteString.copyFrom(mSig))
                    .putAllKeys(mPubKeymap)
                    .build();

            return true;
        } else {
            return false;
        }
    }


    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            this.mIdentity.writeDelimitedTo(os);
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

    private void generateKeyPair() throws GeneralSecurityException, IOException {
        byte[] privkey = new byte[Sign.ED25519_SECRETKEYBYTES];
        this.mScatterbrainPubKey = new byte[Sign.ED25519_PUBLICKEYBYTES];

        LibsodiumInterface.getSodium().crypto_sign_keypair(mScatterbrainPubKey, privkey);

        this.mPubKeymap.put(PROTOBUF_PRIVKEY_KEY, ByteString.copyFrom(mScatterbrainPubKey));
        String secretKeyBase64 = LibsodiumInterface.base64enc(privkey);
        String fingerprint = LibsodiumInterface.base64enc(mScatterbrainPubKey);

        mKeystorePrefs.edit()
                .putString(fingerprint, secretKeyBase64)
                .apply();
        signEd25519(privkey);
    }

    public Map<String, ByteString> getKeymap() {
        return mPubKeymap;
    }

    public byte[] getPubkey() {
        return mScatterbrainPubKey;
    }

    public byte[] getSig() {
        return mSig;
    }

    public String getName() {
        return mGivenName;
    }

    public static Builder newBuilder(Context ctx) {
        return new Builder(ctx);
    }

    public static class Builder {
        private byte[] mScatterbrainPubkey;
        private final Context mCtx;
        private boolean mGenerateKeypair;
        private String mGivenName;
        private byte[] mSig;

        private Builder(Context ctx) {
            this.mCtx = ctx;
            this.mGenerateKeypair = false;
        }

        public Context getContext() {
            return mCtx;
        }

        public byte[] getScatterbrainPubkey() {
            return mScatterbrainPubkey;
        }

        public boolean ismGenerateKeypair() {
            return mGenerateKeypair;
        }

        public String getName() {
            return mGivenName;
        }

        public byte[] getSig() { return mSig; }

        public Builder generateKeypair() {
            this.mGenerateKeypair = true;
            return this;
        }

        public Builder setName(String name) {
            this.mGivenName = name;
            return this;
        }

        public Builder setSig(byte[] sig) {
            this.mSig = sig;
            return this;
        }

        public Builder setScatterbrainPubkey(ByteString pubkey) {
            mScatterbrainPubkey = pubkey.toByteArray();
            return this;
        }

        public Identity build() {

            if (mScatterbrainPubkey == null && ! mGenerateKeypair) {
                return null;
            }

            if (mScatterbrainPubkey != null && mGenerateKeypair) {
                return null;
            }

            if (this.mGivenName == null) {
                return null;
            }

            try {
                return new Identity(this);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }


    /* implementation for Map<String, ByteString> */

    @Override
    public int size() {
        return mPubKeymap.size();
    }

    @Override
    public boolean isEmpty() {
        return mPubKeymap.isEmpty();
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        return mPubKeymap.containsKey(key);
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
        return mPubKeymap.containsValue(value);
    }

    @Nullable
    @Override
    public ByteString get(@Nullable Object key) {
        return mPubKeymap.get(key);
    }

    @Nullable
    @Override
    public ByteString put(String key, ByteString value) {
        return mPubKeymap.put(key, value);
    }

    @Nullable
    @Override
    public ByteString remove(@Nullable Object key) {
        return mPubKeymap.remove(key);
    }

    @Override
    public void putAll(@NonNull Map<? extends String, ? extends ByteString> m) {
        mPubKeymap.putAll(m);
    }

    @Override
    public void clear() {
        mPubKeymap.clear();
    }

    @NonNull
    @Override
    public Set<String> keySet() {
        return mPubKeymap.keySet();
    }

    @NonNull
    @Override
    public Collection<ByteString> values() {
        return mPubKeymap.values();
    }

    @NonNull
    @Override
    public Set<Entry<String, ByteString>> entrySet() {
        return mPubKeymap.entrySet();
    }


}
