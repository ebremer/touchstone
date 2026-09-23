package com.ebremer.touchstone.core.engine;

import java.math.BigInteger;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

/**
 * did:key identifiers for P-256 keys (EXECUTION.md section 5.3): {@code did:key:z} +
 * base58btc(varint 0x1200 ‖ compressed point), so every identifier starts {@code did:key:zDn}.
 * The multicodec 0x1200 is {@code p256-pub}; its unsigned varint is the two bytes 0x80 0x24.
 */
final class DidKeys {

    private static final byte[] P256_PUB = {(byte) 0x80, (byte) 0x24};
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private DidKeys() {
    }

    static String did(ECKey key) {
        if (!Curve.P_256.equals(key.getCurve())) {
            throw new IllegalArgumentException("a did:key here is a P-256 key, not " + key.getCurve());
        }
        byte[] x = unsigned32(key.getX().decodeToBigInteger());
        boolean odd = key.getY().decodeToBigInteger().testBit(0);
        byte[] bytes = new byte[P256_PUB.length + 33];
        System.arraycopy(P256_PUB, 0, bytes, 0, P256_PUB.length);
        bytes[P256_PUB.length] = (byte) (odd ? 0x03 : 0x02);
        System.arraycopy(x, 0, bytes, P256_PUB.length + 1, 32);
        return "did:key:z" + base58(bytes);
    }

    private static byte[] unsigned32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    /** Bitcoin base58: leading zero bytes become '1', the rest is the number in base 58. */
    static String base58(byte[] input) {
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        BigInteger n = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        BigInteger base = BigInteger.valueOf(58);
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(base);
            sb.append(ALPHABET.charAt(qr[1].intValue()));
            n = qr[0];
        }
        sb.append("1".repeat(zeros));
        return sb.reverse().toString();
    }
}
