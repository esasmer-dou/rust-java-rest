package com.reactor.rust.security;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

/**
 * Immutable bounded local trust set. Each kid is bound to exactly one algorithm and key.
 * X.509 key encodings are snapshotted through JCA at startup; token-directed key loading,
 * URLs, JWKS refresh and network access are intentionally unsupported. Rotate by replacing
 * the entire verifier/cache, never by mutating a live key set.
 */
public final class JwtKeySet {
    public static final int MAX_KEYS = 64;
    public static final int MAX_KID_CHARS = 128;
    private final Key[] keys;

    public record Key(String kid, JwtAlgorithm algorithm, PublicKey publicKey) {
        public Key {
            Objects.requireNonNull(kid, "kid");
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(publicKey, "publicKey");
            if (kid.isBlank() || kid.length() > MAX_KID_CHARS) throw new IllegalArgumentException("Invalid kid bound");
        }
    }

    public JwtKeySet(Key... supplied) {
        Objects.requireNonNull(supplied, "keys");
        if (supplied.length == 0 || supplied.length > MAX_KEYS) throw new IllegalArgumentException("JWT key count must be 1..64");
        keys = new Key[supplied.length];
        for (int i = 0; i < keys.length; i++) {
            Key source = Objects.requireNonNull(supplied[i], "key");
            for (int j = 0; j < i; j++) {
                if (keys[j].kid().equals(source.kid())) throw new IllegalArgumentException("Duplicate JWT kid");
            }
            if (source.algorithm() == JwtAlgorithm.HS256) throw new IllegalArgumentException("HS256 cannot use a public key");
            try {
                byte[] encoded = source.publicKey().getEncoded();
                if (encoded == null || encoded.length == 0 || encoded.length > 4096) {
                    throw new IllegalArgumentException("JWT public key encoding exceeds bound");
                }
                PublicKey snapshot = KeyFactory.getInstance(source.algorithm().keyFactoryName)
                        .generatePublic(new X509EncodedKeySpec(encoded));
                validate(source.algorithm(), snapshot);
                keys[i] = new Key(source.kid(), source.algorithm(), snapshot);
            } catch (GeneralSecurityException error) {
                throw new IllegalArgumentException("JWT key does not match its pinned algorithm", error);
            }
        }
    }

    private static void validate(JwtAlgorithm algorithm, PublicKey key) throws GeneralSecurityException {
        switch (algorithm) {
            case RS256 -> {
                if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048
                        || rsa.getModulus().bitLength() > 8192 || rsa.getPublicExponent().signum() <= 0
                        || !rsa.getPublicExponent().testBit(0) || rsa.getPublicExponent().bitLength() < 2
                        || rsa.getPublicExponent().bitLength() > 32) {
                    throw new IllegalArgumentException("RS256 requires a 2048..8192 bit RSA key with bounded odd exponent");
                }
            }
            case ES256 -> {
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
                if (!(key instanceof ECPublicKey ec) || !expected.getCurve().equals(ec.getParams().getCurve())
                        || !expected.getGenerator().equals(ec.getParams().getGenerator())
                        || !expected.getOrder().equals(ec.getParams().getOrder())
                        || expected.getCofactor() != ec.getParams().getCofactor()) {
                    throw new IllegalArgumentException("ES256 requires exactly secp256r1/P-256");
                }
            }
            case EdDSA -> {
                if (!(key instanceof EdECPublicKey ed) || !"Ed25519".equals(ed.getParams().getName())) {
                    throw new IllegalArgumentException("EdDSA requires Ed25519, not Ed448");
                }
            }
            default -> throw new IllegalArgumentException("Public-key JWT algorithm required");
        }
    }

    public int size() { return keys.length; }

    Key find(String kid) {
        for (Key key : keys) if (key.kid().equals(kid)) return key;
        return null;
    }

    Key first(JwtAlgorithm algorithm) {
        for (Key key : keys) if (key.algorithm() == algorithm) return key;
        return null;
    }
}
