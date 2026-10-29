package com.reactor.rust.security;

/** JOSE identifiers are case-sensitive. HS256 remains on the separate legacy symmetric path. */
public enum JwtAlgorithm {
    HS256(null, null),
    RS256("SHA256withRSA", "RSA"),
    ES256("SHA256withECDSAinP1363Format", "EC"),
    EdDSA("Ed25519", "Ed25519");

    final String signatureName;
    final String keyFactoryName;

    JwtAlgorithm(String signatureName, String keyFactoryName) {
        this.signatureName = signatureName;
        this.keyFactoryName = keyFactoryName;
    }
}
