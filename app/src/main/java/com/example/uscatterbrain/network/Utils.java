package com.example.uscatterbrain.network;

public class Utils {
    public static byte[][] splitChunks(byte[] source)
    {
        final int CHUNK_SIZE = 10;
        byte[][] ret = new byte[(int)Math.ceil(source.length / (double)CHUNK_SIZE)][];
        int start = 0;
        for(int i = 0; i < ret.length; i++) {
            if(start + CHUNK_SIZE > source.length) {
                ret[i] = new byte[source.length-start];
                System.arraycopy(source, start, ret[i], 0, source.length - start);
            }
            else {
                ret[i] = new byte[CHUNK_SIZE];
                System.arraycopy(source, start, ret[i], 0, CHUNK_SIZE);
            }
            start += CHUNK_SIZE ;
        }
        return ret;
    }
}
