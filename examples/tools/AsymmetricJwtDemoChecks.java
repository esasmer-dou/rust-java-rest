import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;

/** Small JDK-only checks, to be compiled and executed on EmaTech, not locally. */
public final class AsymmetricJwtDemoChecks {
    public static void main(String[] args) throws Exception {
        var work = Files.createTempDirectory("jwt-demo-check-");
        try {
            var keys = work.resolve("issuer");
            AsymmetricJwtDemo.generateKeys(keys);
            long now = 1_800_000_000L;
            for (var algorithm : AsymmetricJwtDemo.ALGORITHMS) {
                var parts = AsymmetricJwtDemo.token(keys, algorithm, "reader", now).split("\\.");
                require(parts.length == 3, "three segments");
                require(!String.join("", parts).contains("="), "unpadded base64url");
                var decoder = Base64.getUrlDecoder();
                String header = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8);
                String claims = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);
                require(header.contains("\"alg\":\"" + algorithm + "\""), "algorithm");
                require(header.contains("\"kid\":\"" + algorithm + "\""), "key ID");
                for (String value : new String[]{"sample-issuer", "sample-api", "sample-user", "reader",
                        "\"iat\":" + now, "\"exp\":" + (now + 300)}) {
                    require(claims.contains(value), "claim " + value);
                }
                String keyName = Map.of("RS256", "RSA", "ES256", "EC", "EdDSA", "Ed25519").get(algorithm);
                String signatureName = Map.of("RS256", "SHA256withRSA", "ES256",
                        "SHA256withECDSAinP1363Format", "EdDSA", "Ed25519").get(algorithm);
                var key = KeyFactory.getInstance(keyName).generatePublic(new X509EncodedKeySpec(
                        Files.readAllBytes(keys.resolve("public").resolve(algorithm + ".der"))));
                var verifier = Signature.getInstance(signatureName);
                byte[] signature = decoder.decode(parts[2]);
                if (algorithm.equals("ES256")) require(signature.length == 64, "JWS P1363 ES256");
                verifier.initVerify(key);
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                require(verifier.verify(signature), "signature round trip");
                verifier.initVerify(key);
                verifier.update((parts[0] + "." + parts[1] + "x").getBytes(StandardCharsets.US_ASCII));
                require(!verifier.verify(signature), "tampering rejected");
            }
            var decoder = Base64.getUrlDecoder();
            for (var entry : Map.of("admin", "\"admin\"", "expired", "\"exp\":" + (now - 300),
                    "wrong-issuer", "other-issuer", "wrong-audience", "other-api").entrySet()) {
                var token = AsymmetricJwtDemo.token(keys, "RS256", entry.getKey(), now);
                require(new String(decoder.decode(token.split("\\.")[1]), StandardCharsets.UTF_8)
                        .contains(entry.getValue()), "negative/admin scenario");
            }
            require(new String(decoder.decode(AsymmetricJwtDemo.token(keys, "RS256", "wrong-kid", now)
                    .split("\\.")[0]), StandardCharsets.UTF_8).contains("unknown"), "unknown kid");
            try {
                AsymmetricJwtDemo.generateKeys(keys);
                throw new AssertionError("key overwrite allowed");
            } catch (java.nio.file.FileAlreadyExistsException expected) { }
            for (String[] input : new String[][]{{"HS256", "reader"}, {"RS256", "unknown"}}) {
                try {
                    AsymmetricJwtDemo.token(keys, input[0], input[1], now);
                    throw new AssertionError("invalid input allowed");
                } catch (IllegalArgumentException expected) { }
            }
            System.out.println("JWT development helper checks passed (not service acceptance)");
        } finally {
            try (var paths = Files.walk(work)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
