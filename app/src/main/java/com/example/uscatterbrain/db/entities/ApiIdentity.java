package com.example.uscatterbrain.db.entities;

import com.example.uscatterbrain.API.Identity;
import com.example.uscatterbrain.network.IdentityPacket;

import java.util.HashMap;
import java.util.Map;

public class ApiIdentity extends Identity {

    protected ApiIdentity(Builder builder) {
        super(
                builder.mPubKeymap,
                builder.mPubKeymap.get(IdentityPacket.PROTOBUF_PRIVKEY_KEY),
                builder.name,
                builder.sig
        );
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
            return new ApiIdentity(this);
        }
    }
}
