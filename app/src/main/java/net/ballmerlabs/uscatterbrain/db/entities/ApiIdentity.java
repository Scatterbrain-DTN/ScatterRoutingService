package net.ballmerlabs.uscatterbrain.db.entities;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import net.ballmerlabs.uscatterbrain.API.Identity;
import net.ballmerlabs.uscatterbrain.network.IdentityPacket;
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public class ApiIdentity extends Identity {
    private byte[] privatekey;

    protected ApiIdentity(Builder builder) {
        super(
                builder.mPubKeymap,
                builder.mPubKeymap.get(IdentityPacket.PROTOBUF_PRIVKEY_KEY),
                builder.name,
                builder.sig
        );

        this.privatekey = builder.privkey;
    }

    @Nullable
    public byte[] getPrivateKey() {
        return privatekey;
    }

    public static class KeyPair {
        public final byte[] secretkey;
        public final byte[] publickey;
        private KeyPair(byte[] sec, byte[] pub) {
            this.publickey = pub;
            this.secretkey = sec;
        }
    }

    public static KeyPair newPrivateKey() {
        byte[] sec = new byte[Sign.SECRETKEYBYTES];
        byte[] pub = new byte[Sign.PUBLICKEYBYTES];

        LibsodiumInterface.getSodium().crypto_sign_keypair(pub, sec);

        return new KeyPair(sec, pub);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private byte[] sig;
        private Map<String, byte[]> mPubKeymap = new HashMap<>();
        private String name;
        private byte[] pubkey;
        private byte[] privkey;
        private KeyPair signPair;
        private Builder() {
            super();
        }

        private ByteString sumBytes() {
            ByteString result = ByteString.EMPTY;
            result = result.concat(ByteString.copyFromUtf8(name));
            SortedSet<String> sortedKeys= new TreeSet<>(mPubKeymap.keySet());
            for (String key : sortedKeys) {
                result = result.concat(ByteString.copyFromUtf8(key));
                final byte[] k = mPubKeymap.get(key);
                if (k == null) {
                    throw new ConcurrentModificationException();
                }
                result = result.concat(ByteString.copyFrom(k));
            }
            return result;
        }

        /**
         * Sign ed 25519 boolean.
         *
         * @param secretkey the secretkey
         * @return the boolean
         */
        private synchronized boolean signEd25519(byte[] secretkey) {
            if (secretkey.length != Sign.SECRETKEYBYTES)
                return false;

            ByteString messagebytes = sumBytes();

            byte[] signature = new byte[Sign.ED25519_BYTES];
            Pointer p = new PointerByReference(Pointer.NULL).getPointer();
            if (LibsodiumInterface.getSodium().crypto_sign_detached(signature,
                    p, messagebytes.toByteArray(), messagebytes.size(), secretkey) == 0) {
                this.sig = signature;
                return true;
            } else {
                return false;
            }
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder sign(KeyPair keyPair) {
            this.signPair = keyPair;
            return this;
        }

        public Builder setSig(byte[] sig) {
            this.sig = sig;
            return this;
        }

        public Builder addKeys(Map<String, byte[]> keys) {
            this.mPubKeymap.putAll(keys);
            return this;
        }

        public ApiIdentity build() {
            if (name == null) {
                throw new IllegalArgumentException("name should be non-null");
            }

            if (sig == null && signPair == null) {
                throw new IllegalArgumentException("sig should be set");
            }

            if (sig != null && signPair != null) {
                throw new IllegalArgumentException("cannot sign and set sig simultaneously");
            }

            if (signPair != null) {
                signEd25519(signPair.secretkey);
                mPubKeymap.put(IdentityPacket.PROTOBUF_PRIVKEY_KEY, signPair.publickey);
                this.pubkey = signPair.publickey;
            } else {
                if (!mPubKeymap.containsKey(IdentityPacket.PROTOBUF_PRIVKEY_KEY)) {
                    throw new IllegalArgumentException("key map does not contain scatterbrain pubkey");
                }

                pubkey = mPubKeymap.get(IdentityPacket.PROTOBUF_PRIVKEY_KEY);
            }
            return new ApiIdentity(this);
        }
    }
}
