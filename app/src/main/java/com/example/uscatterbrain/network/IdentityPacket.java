package com.example.uscatterbrain.network;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.example.uscatterbrain.ScatterProto;
import com.github.davidmoten.rx2.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import com.goterl.lazycode.lazysodium.interfaces.GenericHash;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public class IdentityPacket implements Map<String, ByteString>, ScatterSerializable {
    private Map<String, ByteString>  mPubKeymap = new TreeMap<>();
    private SharedPreferences mKeystorePrefs;
    private final Context mCtx;
    private final AtomicReference<ScatterProto.Identity> mIdentity = new AtomicReference<>();
    private final String givenname;
    private final AtomicReference<ByteString> sig = new AtomicReference<>(ByteString.EMPTY);
    private final byte[] mScatterbrainPubKey;
    private UUID luidtag;
    public static final String PROTOBUF_PRIVKEY_KEY = "scatterbrain";
    public static final String KEYSTORE_ID = "scatterbrainkeystore";

    private IdentityPacket(Builder builder) throws GeneralSecurityException, IOException {
        this.mCtx = builder.getContext();
        byte[] sig  = builder.getSig();
        if (sig != null) {
            this.sig.set(ByteString.copyFrom(sig));
        }
        mScatterbrainPubKey = builder.getScatterbrainPubkey();
        givenname = builder.mGivenName;
        initKeyStore();
        if (builder.ismGenerateKeypair()) {
            generateKeyPair();
        }


        if (builder.gone) {
            this.mIdentity.set(ScatterProto.Identity.newBuilder()
                    .setEnd(true)
                    .build());
        } else {
            this.mPubKeymap.put(PROTOBUF_PRIVKEY_KEY, ByteString.copyFrom(mScatterbrainPubKey));
            regenIdentity();
        }
    }

    private void regenIdentity() {
        ScatterProto.Identity.Body body = ScatterProto.Identity.Body.newBuilder()
                .setGivenname(givenname)
                .setSig(this.sig.get())
                .putAllKeys(mPubKeymap)
                .build();
        this.mIdentity.set(ScatterProto.Identity.newBuilder()
                .setVal(body)
                .setEnd(false)
                .build());
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

    public boolean isEnd() {
        return mIdentity.get().getMessageCase().equals(ScatterProto.Identity.MessageCase.END);
    }


    public String getFingerprint() {
        byte[] fingeprint = new byte[GenericHash.BYTES];
        LibsodiumInterface.getSodium().crypto_generichash(
                fingeprint,
                fingeprint.length,
                mScatterbrainPubKey,
                mScatterbrainPubKey.length,
                null,
                0
        );

        return LibsodiumInterface.base64enc(fingeprint);
    }

    private IdentityPacket(InputStream is, Context mCtx) throws IOException, GeneralSecurityException {
        ScatterProto.Identity identity = ScatterProto.Identity.parseDelimitedFrom(is);
        this.mCtx = mCtx;
        if (identity.getMessageCase().equals(ScatterProto.Identity.MessageCase.VAL)) {
            initKeyStore();
            this.mPubKeymap = identity.getVal().getKeysMap();
            this.sig.set(identity.getVal().getSig());
            this.givenname = identity.getVal().getGivenname();
            ByteString scatterbrainKey = mPubKeymap.get(PROTOBUF_PRIVKEY_KEY);
            if (scatterbrainKey == null) {
                throw new ProtocolException("scatterbrain key not in map");
            }
            this.mScatterbrainPubKey = scatterbrainKey.toByteArray();
            regenIdentity();
        } else {
            this.mScatterbrainPubKey = null;
            this.givenname = null;
            this.sig.set(null);
            this.mIdentity.set(ScatterProto.Identity.newBuilder()
                    .setEnd(true)
                    .build());
        }
    }


    public static Single<IdentityPacket> parseFrom(InputStream is, Context ctx) {
        return Single.fromCallable(() -> new IdentityPacket(is, ctx));
    }

    public static Single<IdentityPacket> parseFrom(Observable<byte[]> flowable, Context ctx) {
        InputStreamObserver observer = new InputStreamObserver();
        flowable.subscribe(observer);
        return IdentityPacket.parseFrom(observer, ctx).doFinally(observer::close);
    }

    public static Single<IdentityPacket> parseFrom(Flowable<byte[]> flowable, Context ctx) {
        InputStreamFlowableSubscriber observer = new InputStreamFlowableSubscriber();
        flowable.subscribe(observer);
        return IdentityPacket.parseFrom(observer, ctx).doFinally(observer::close);
    }

    public ByteString sumBytes() {
        if (isEnd()) {
            return null;
        }
        ByteString result = ByteString.EMPTY;
        result = result.concat(ByteString.copyFromUtf8(givenname));
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
        if (isEnd()) {
            return false;
        }
        if (pubkey.length != Sign.PUBLICKEYBYTES)
            return false;

        ByteString messagebytes = sumBytes();
        return LibsodiumInterface.getSodium().crypto_sign_verify_detached(sig.get().toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size(),
                pubkey) == 0;
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
            regenIdentity();
            return true;
        } else {
            return false;
        }
    }


    @Override
    public byte[] getBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            this.mIdentity.get().writeDelimitedTo(os);
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
        return Completable.fromAction(() -> mIdentity.get().writeDelimitedTo(os));
    }

    @Override
    public Flowable<byte[]> writeToStream(int fragsize) {
        return Bytes.from(new ByteArrayInputStream(getBytes()), fragsize);
    }

    @Override
    public GeneratedMessageLite getMessage() {
        return mIdentity.get();
    }

    private void generateKeyPair() throws GeneralSecurityException, IOException {
        byte[] privkey = new byte[Sign.ED25519_SECRETKEYBYTES];
        if (mScatterbrainPubKey.length != Sign.ED25519_PUBLICKEYBYTES) {
            throw new IOException("public key length mismatch");
        }

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
        return sig.get().toByteArray();
    }

    public String getName() {
        return givenname;
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
        boolean gone;

        private Builder(Context ctx) {
            this.mCtx = ctx;
            this.mGenerateKeypair = false;
            this.gone = false;
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

        public Builder setEnd() {
            this.gone = true;
            return this;
        }

        public Builder setEnd(boolean end) {
            this.gone = end;
            return this;
        }

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

        public IdentityPacket build() {

            if (!gone) {
                if (mScatterbrainPubkey == null && !mGenerateKeypair) {
                    return null;
                }

                if (mScatterbrainPubkey != null && mGenerateKeypair) {
                    return null;
                }

                if (this.mGivenName == null) {
                    return null;
                }
            }

            try {
                return new IdentityPacket(this);
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
