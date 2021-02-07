package net.ballmerlabs.uscatterbrain.network;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

public class CRCProtobuf {

    private static final long MASK = 0xFFFFFFFFL;
    private static final int MESSAGE_SIZE_CAP = 1024*1024;

    public static long bytes2long(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getInt() & MASK;
    }

    public static byte[] longToByte(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt((int)value);
        return buffer.array();
    }

    public static <T extends MessageLite> T parseFromCRC(Parser<T> parser, InputStream inputStream) throws IOException {
        byte[] crc = new byte[4];
        byte[] size = new byte[4];
        if (inputStream.read(size) != 4) {
            throw new IOException("end of stream");
        }
        int s = ByteBuffer.wrap(size).order(ByteOrder.BIG_ENDIAN).getInt();

        if (s > MESSAGE_SIZE_CAP) {
            throw new IOException("invalid message size");
        }

        CodedInputStream co = CodedInputStream.newInstance(inputStream, s+1);
        byte[] messageBytes = co.readRawBytes(s);
        T message = parser.parseFrom(messageBytes);
        if (inputStream.read(crc) != crc.length) {
            throw new IOException("end of stream");
        }
        CRC32 crc32 = new CRC32();
        crc32.update(messageBytes);
        if (crc32.getValue() != bytes2long(crc)) {
            throw new IOException("invalid crc: " + crc32.getValue() + " " + bytes2long(crc));
        }
        return message;
    }

    public static void writeToCRC(MessageLite message, OutputStream outputStream) throws IOException {
        outputStream.write(
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(message.getSerializedSize()).array()
                );
        byte[] out = message.toByteArray();
        CRC32 crc32 = new CRC32();
        crc32.update(out);
        outputStream.write(out);
        outputStream.write(longToByte(crc32.getValue()));
    }
}
