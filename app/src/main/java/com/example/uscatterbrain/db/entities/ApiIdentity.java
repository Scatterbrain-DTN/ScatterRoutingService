package com.example.uscatterbrain.db.entities;

import com.example.uscatterbrain.API.Identity;
import com.example.uscatterbrain.network.IdentityPacket;
import com.google.protobuf.ByteString;

import java.util.Map;

public class ApiIdentity extends Identity {

    protected ApiIdentity(Builder builder) {
        super(builder);
    }


    public static Builder newBuilder() {
        return new Builder();
    }

    protected void setSig(byte[] sig) {
        this.sig.set(ByteString.copyFrom(sig));
    }

    public static class Builder extends Identity.Builder {
        private byte[] sig;
        private Builder() {
            super();
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder sign(byte[] sig) {
            this.sig = sig;
            return this;
        }

        public Builder addKeys(Map<String, byte[]> keys) {
            this.mPubKeymap.putAll(keys);
            return this;
        }

        public com.example.uscatterbrain.API.Identity build() {
            if (name == null) {
                throw new IllegalArgumentException("name should be non-null");
            }

            if (sig == null) {
                throw new IllegalArgumentException("sig should be set");
            }

            mPubKeymap.put(IdentityPacket.PROTOBUF_PRIVKEY_KEY, pubkey);
            final ApiIdentity identity = new ApiIdentity(this);
            identity.setSig(sig);
            return identity;
        }
    }
}
