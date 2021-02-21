package net.ballmerlabs.uscatterbrain.db;

import com.google.protobuf.ByteString;
import com.goterl.lazycode.lazysodium.interfaces.Sign;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import net.ballmerlabs.scatterbrainsdk.ScatterMessage;
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity;
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ApiScatterMessage extends ScatterMessage {
    private final AtomicReference<byte[]> secretkey = new AtomicReference<>();
    private ApiScatterMessage(Builder builder) {
        super(builder);
        this.secretkey.set(builder.privatekey);
    }

    private static Builder superToBuilder(ScatterMessage message) {
        final Builder builder = newBuilder();
        if (message.toDisk()) {
            builder
                    .setFile(
                            message.getFileDescriptor(),
                            message.getExtension(),
                            message.getMime(),
                            message.getFilename()
                    );

        } else {
            builder.setBody(message.getBody());
        }
        builder
                .setApplication(message.getApplication())
                .setFrom(message.getFromFingerprint())
                .setTo(message.getToFingerprint());
        return builder;
    }

    private ApiScatterMessage(ScatterMessage message) {
        super(superToBuilder(message));
    }

    private ApiScatterMessage(ScatterMessage message, byte[] privateKey) {
        super(superToBuilder(message));
        this.secretkey.set(privateKey);
    }

    private ByteString sumBytes(List<ByteString> hashes) {
        ByteString messagebytes = ByteString.EMPTY;
        messagebytes = messagebytes.concat(ByteString.copyFrom(fromFingerprint));
        messagebytes = messagebytes.concat(ByteString.copyFrom(toFingerprint));
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.application));
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.extension));
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.mime));
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.filename));
        byte td = 0;
        if (toDisk())
            td = 1;
        ByteString toDiskBytes = ByteString.copyFrom(ByteBuffer.allocate(1).order(ByteOrder.BIG_ENDIAN).put(td).array());
        messagebytes = messagebytes.concat(toDiskBytes);

        for (ByteString hash : hashes) {
            messagebytes = messagebytes.concat(hash);
        }
        return messagebytes;
    }

    /**
     * Sign ed 25519 boolean.
     *
     * @param hashes
     * @return the boolean
     */
    public synchronized void signEd25519(List<ByteString> hashes) {
        final byte[] pk = secretkey.get();
        if (pk == null) {
            throw new IllegalStateException("secret key not set");
        }

        if (pk.length != Sign.SECRETKEYBYTES)
            throw new IllegalStateException("secret key wrong length");

        ByteString messagebytes = sumBytes(hashes);

        byte[] localsig  = new byte[Sign.ED25519_BYTES];
        Pointer p = new PointerByReference(Pointer.NULL).getPointer();
        if (LibsodiumInterface.getSodium().crypto_sign_detached(localsig,
                p, messagebytes.toByteArray(), messagebytes.size(), pk) == 0) {
            sig.set(localsig);
        } else {
            secretkey.set(null);
            throw new IllegalStateException("crypto_sign_detached failed");
        }
        secretkey.set(null);
    }

    public boolean signable() {
        return secretkey.get() != null && secretkey.get().length == Sign.SECRETKEYBYTES;
    }

    public static ApiScatterMessage fromApi(ScatterMessage message) {
        return new ApiScatterMessage(message);
    }

    public static ApiScatterMessage fromApi(ScatterMessage message, ApiIdentity.KeyPair pair) {
        return new ApiScatterMessage(message, pair.secretkey);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder extends ScatterMessage.Builder {
        private byte[] privatekey;
        private Builder() {
            super();
        }

        public Builder sign(ApiIdentity.KeyPair keyPair, String fingerprint) {
            this.privatekey = keyPair.secretkey;
            this.fingerprint = fingerprint;
            return this;
        }

        public ApiScatterMessage build() {
            verify();
            return new ApiScatterMessage(this);
        }
    }
}
