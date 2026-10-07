package com.dbu.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Genesys webhook signature")
class GenesysSignatureVerifierTest {

    private static final String SECRET = "test-secret";
    private static final String OLD_SECRET = "previous-secret";
    private static final byte[] BODY =
            "{\"id\":\"msg-1\",\"text\":\"Hi, this is Sara\"}".getBytes(StandardCharsets.UTF_8);

    private final GenesysSignatureVerifier verifier =
            new GenesysSignatureVerifier(SECRET, OLD_SECRET);

    @Test
    void acceptsASignatureGeneratedTheWayGenesysGeneratesIt() {
        assertThat(verifier.isValid(sign(BODY, SECRET), BODY)).isTrue();
    }

    @Test
    void theDigestIsBase64NotHex() {
        // The same header name is used elsewhere with a hex digest. Getting this
        // wrong rejects every delivery, with nothing in the payload to explain
        // why, so it is worth asserting explicitly.
        String hexStyle = "sha256=" + hexDigest(BODY, SECRET);

        assertThat(verifier.isValid(hexStyle, BODY)).isFalse();
        assertThat(verifier.isValid(sign(BODY, SECRET), BODY)).isTrue();
    }

    @Test
    void rejectsAModifiedBody() {
        String signature = sign(BODY, SECRET);
        byte[] tampered = "{\"id\":\"msg-1\",\"text\":\"Send me your password\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(signature, tampered)).isFalse();
    }

    @Test
    void rejectsAReserialisedBody() {
        // Proof that the raw bytes matter. This is the same JSON with different
        // whitespace — what any gateway that reformats the body would produce.
        byte[] reformatted = "{\n  \"id\": \"msg-1\",\n  \"text\": \"Hi, this is Sara\"\n}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(sign(BODY, SECRET), reformatted)).isFalse();
    }

    @Test
    void rejectsASignatureMadeWithTheWrongSecret() {
        assertThat(verifier.isValid(sign(BODY, "not-the-secret"), BODY)).isFalse();
    }

    @Test
    void rejectsAHeaderWithoutThePrefix() {
        String withoutPrefix = sign(BODY, SECRET).substring("sha256=".length());

        assertThat(verifier.isValid(withoutPrefix, BODY)).isFalse();
    }

    @Test
    void rejectsAMissingHeader() {
        assertThat(verifier.isValid(null, BODY)).isFalse();
    }

    @Test
    void acceptsThePreviousSecretDuringRotation() {
        // Genesys and this service switch secrets at slightly different moments.
        // Without this window, every delivery in between would be rejected.
        assertThat(verifier.isValid(sign(BODY, OLD_SECRET), BODY)).isTrue();
    }

    @Test
    void rejectsEverythingWhenNoSecretIsConfigured() {
        GenesysSignatureVerifier unconfigured = new GenesysSignatureVerifier("", "");

        assertThat(unconfigured.isValid(sign(BODY, SECRET), BODY)).isFalse();
    }

    /** Mirrors the Postman pre-request script supplied by the Genesys team. */
    private static String sign(byte[] body, String secret) {
        return "sha256=" + Base64.getEncoder().encodeToString(hmac(body, secret));
    }

    private static String hexDigest(byte[] body, String secret) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hmac(body, secret)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hmac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
