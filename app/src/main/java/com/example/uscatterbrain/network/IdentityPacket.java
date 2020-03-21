package com.example.uscatterbrain.network;

import com.example.uscatterbrain.ScatterProto;
import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public class IdentityPacket implements ScatterSerializable {

    private ScatterProto.Identity mIdentity;
    private String mGivenName;
    private Map<String, ByteString> mKeys;
    private ByteString mSig;

    public IdentityPacket(Builder builder) {
        this.mGivenName = builder.getName();
        this.mKeys = builder.getKeys();
        this.mSig = builder.getSig();
        this.mIdentity = ScatterProto.Identity.newBuilder()
                .setGivenname(mGivenName)
                .setSig(mSig)
                .putAllKeys(mKeys)
                .build();
    }

    public IdentityPacket(InputStream is) throws IOException {
        this.mIdentity = ScatterProto.Identity.parseDelimitedFrom(is);
        this.mGivenName = mIdentity.getGivenname();
        this.mKeys = mIdentity.getKeysMap();
        this.mSig = mIdentity.getSig();
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
    public boolean writeToStream(OutputStream os) {
        try {
            mIdentity.writeDelimitedTo(os);
        } catch (IOException e) {
            return false;
        }

        return true;
    }

    @Override
    public int size() {
        return 0;
    }


    public String getName() {
        return mGivenName;
    }

    public Map<String, ByteString> getKeys() {
        return mKeys;
    }

    public ByteString getSig() {
        return mSig;
    }


    public static class Builder {
        private String mGivenName;
        private Map<String, ByteString> mKeys;
        private byte[] mPrivateKey;
        private ByteString mSig;

        public Builder() {

        }

        public Builder setName(String name) {
            this.mGivenName = name;
            return this;
        }

        public Builder setKeys(Map<String, ByteString> keys) {
            this.mKeys = keys;
            return this;
        }

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

        public String getName() {
            return mGivenName;
        }

        public Map<String, ByteString> getKeys() {
            return mKeys;
        }

        public ByteString getSig() {
            return mSig;
        }

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
