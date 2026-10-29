import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/** Development issuer only. Never put these private keys or this issuer in the REST service. */
public final class AsymmetricJwtDemo {
    static final Set<String> ALGORITHMS = Set.of("RS256", "ES256", "EdDSA");
    static final Set<String> SCENARIOS = Set.of(
            "reader", "admin", "expired", "wrong-issuer", "wrong-audience", "wrong-kid");

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("keys")) {
            generateKeys(Path.of(args[1]));
        } else if ((args.length == 3 || args.length == 4) && args[0].equals("token")) {
            System.out.println(token(Path.of(args[1]), args[2],
                    args.length == 4 ? args[3] : "reader", Instant.now().getEpochSecond()));
        } else {
            throw new IllegalArgumentException(
                    "keys <NEW-directory> | token <directory> <RS256|ES256|EdDSA> "
                    + "[reader|admin|expired|wrong-issuer|wrong-audience|wrong-kid]");
        }
    }

    static void generateKeys(Path directory) throws Exception {
        createPrivateDirectory(directory);
        Path privateDirectory = directory.resolve("private");
        createPrivateDirectory(privateDirectory);
        Files.createDirectory(directory.resolve("public"));
        for (String algorithm : ALGORITHMS) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(keyAlgorithm(algorithm));
            if (algorithm.equals("RS256")) generator.initialize(2048);
            if (algorithm.equals("ES256")) generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            Path privateFile = privateDirectory.resolve(algorithm + ".pk8");
            if (supportsPosix(privateDirectory)) {
                Files.createFile(privateFile, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
                Files.write(privateFile, pair.getPrivate().getEncoded(), StandardOpenOption.WRITE);
            } else {
                Files.write(privateFile, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
            }
            Files.write(directory.resolve("public").resolve(algorithm + ".der"),
                    pair.getPublic().getEncoded(), StandardOpenOption.CREATE_NEW);
        }
    }

    static String token(Path directory, String algorithm, String scenario, long now) throws Exception {
        if (!ALGORITHMS.contains(algorithm) || !SCENARIOS.contains(scenario)) {
            throw new IllegalArgumentException("Unsupported algorithm or scenario");
        }
        Path file = directory.resolve("private").resolve(algorithm + ".pk8");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a regular development PKCS8 private-key file");
        }
        byte[] encoded;
        try (var input = Files.newInputStream(file)) {
            encoded = input.readNBytes(4097);
        }
        if (encoded.length == 0 || encoded.length > 4096) throw new IOException("Invalid key size");
        var key = KeyFactory.getInstance(keyAlgorithm(algorithm))
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        String kid = scenario.equals("wrong-kid") ? "unknown" : algorithm;
        String issuer = scenario.equals("wrong-issuer") ? "other-issuer" : "sample-issuer";
        String audience = scenario.equals("wrong-audience") ? "other-api" : "sample-api";
        String roles = scenario.equals("admin") ? "[\"reader\",\"admin\"]" : "[\"reader\"]";
        long issued = scenario.equals("expired") ? now - 600 : now;
        long expiry = scenario.equals("expired") ? now - 300 : now + 300;
        String header = "{\"alg\":\"" + algorithm + "\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience
                + "\",\"sub\":\"sample-user\",\"roles\":" + roles
                + ",\"iat\":" + issued + ",\"exp\":" + expiry + "}";
        String signingInput = base64(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64(claims.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance(signatureAlgorithm(algorithm));
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64(signer.sign());
    }

    static String keyAlgorithm(String algorithm) {
        return switch (algorithm) {
            case "RS256" -> "RSA";
            case "ES256" -> "EC";
            case "EdDSA" -> "Ed25519";
            default -> throw new IllegalArgumentException("Unsupported algorithm");
        };
    }

    static String signatureAlgorithm(String algorithm) {
        return switch (algorithm) {
            case "RS256" -> "SHA256withRSA";
            // JWS ES256 requires the 64-byte r||s encoding, not ASN.1 DER.
            case "ES256" -> "SHA256withECDSAinP1363Format";
            case "EdDSA" -> "Ed25519";
            default -> throw new IllegalArgumentException("Unsupported algorithm");
        };
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (supportsPosix(directory.toAbsolutePath().getParent())) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(directory);
        }
    }
}
