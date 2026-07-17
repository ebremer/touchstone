package com.ebremer.touchstone.fixtures.ssi;

import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

/**
 * An Ed25519 key pair with its {@code did:key} identifier. did:key is simple enough to
 * hand-roll over BouncyCastle (DESIGN.md paragraph 4): the multibase {@code z} +
 * multicodec {@code ed25519-pub} (0xed 0x01) encoding of the raw 32-byte public key. The
 * verifier extracts the public key straight from the identifier — no external resolution.
 */
public final class DidKey {

    /** Multicodec prefix for an Ed25519 public key (unsigned varint of 0xed01). */
    private static final byte[] ED25519_MULTICODEC = {(byte) 0xed, (byte) 0x01};

    private final Ed25519PrivateKeyParameters privateKey;
    private final Ed25519PublicKeyParameters publicKey;
    private final String did;

    private DidKey(Ed25519PrivateKeyParameters privateKey, Ed25519PublicKeyParameters publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.did = "did:key:" + multibaseKey(publicKey.getEncoded());
    }

    public static DidKey generate() {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        return new DidKey((Ed25519PrivateKeyParameters) pair.getPrivate(),
                (Ed25519PublicKeyParameters) pair.getPublic());
    }

    public String did() {
        return did;
    }

    /** The verification-method / kid fragment form: {@code did:key:z...#z...}. */
    public String verificationMethodId() {
        return did + "#" + multibaseKey(publicKey.getEncoded());
    }

    public Ed25519PrivateKeyParameters privateKey() {
        return privateKey;
    }

    public Ed25519PublicKeyParameters publicKey() {
        return publicKey;
    }

    /** {@code z} + base58btc(multicodec ed25519-pub || rawKey). */
    public static String multibaseKey(byte[] rawPublicKey) {
        byte[] prefixed = new byte[ED25519_MULTICODEC.length + rawPublicKey.length];
        System.arraycopy(ED25519_MULTICODEC, 0, prefixed, 0, ED25519_MULTICODEC.length);
        System.arraycopy(rawPublicKey, 0, prefixed, ED25519_MULTICODEC.length, rawPublicKey.length);
        return "z" + Base58.encode(prefixed);
    }

    /** Recovers the Ed25519 public key from a {@code did:key:z...} identifier. */
    public static Ed25519PublicKeyParameters publicKeyFromDid(String did) {
        if (!did.startsWith("did:key:z")) {
            throw new IllegalArgumentException("not a did:key identifier: " + did);
        }
        return publicKeyFromMultibase(did.substring("did:key:".length()));
    }

    /** Recovers the Ed25519 public key from a {@code z...} multibase key (CID publicKeyMultibase). */
    public static Ed25519PublicKeyParameters publicKeyFromMultibase(String multibase) {
        if (multibase.isEmpty() || multibase.charAt(0) != 'z') {
            throw new IllegalArgumentException("not a base58btc multibase value: " + multibase);
        }
        byte[] decoded = Base58.decode(multibase.substring(1));
        if (decoded.length != ED25519_MULTICODEC.length + Ed25519PublicKeyParameters.KEY_SIZE
                || decoded[0] != ED25519_MULTICODEC[0] || decoded[1] != ED25519_MULTICODEC[1]) {
            throw new IllegalArgumentException("not an ed25519-pub multicodec key");
        }
        byte[] raw = Arrays.copyOfRange(decoded, ED25519_MULTICODEC.length, decoded.length);
        return new Ed25519PublicKeyParameters(raw, 0);
    }
}
