package org.appdevforall.xferbench;

import java.util.Arrays;

/** Test data patterns from K2GO-576 step 2: all zero, all one, alternating bits. */
final class Payload {
    private Payload() {}

    static byte[] fill(String pattern, int size) {
        byte[] b = new byte[size];
        switch (pattern) {
            case "ones": Arrays.fill(b, (byte) 0xFF); break;
            case "alt": Arrays.fill(b, (byte) 0x55); break;
            default: break; // zeros
        }
        return b;
    }
}
