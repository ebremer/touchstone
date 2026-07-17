package com.ebremer.touchstone.fixtures.ssi;

import java.math.BigInteger;
import java.util.Arrays;

/** Base58 (Bitcoin alphabet) — the multibase {@code z} encoding used by did:key and CID. */
final class Base58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private Base58() {
    }

    static String encode(byte[] input) {
        if (input.length == 0) {
            return "";
        }
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }
        BigInteger value = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] divmod = value.divideAndRemainder(BASE);
            value = divmod[0];
            sb.append(ALPHABET.charAt(divmod[1].intValue()));
        }
        for (int i = 0; i < leadingZeros; i++) {
            sb.append(ALPHABET.charAt(0));
        }
        return sb.reverse().toString();
    }

    static byte[] decode(String input) {
        if (input.isEmpty()) {
            return new byte[0];
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            int digit = ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base58 character: " + input.charAt(i));
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] bytes = value.toByteArray();
        // strip the sign byte BigInteger may prepend
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        int leadingZeros = 0;
        while (leadingZeros < input.length() && input.charAt(leadingZeros) == ALPHABET.charAt(0)) {
            leadingZeros++;
        }
        byte[] out = new byte[leadingZeros + bytes.length];
        System.arraycopy(bytes, 0, out, leadingZeros, bytes.length);
        return out;
    }
}
