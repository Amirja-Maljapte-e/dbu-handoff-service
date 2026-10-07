package com.dbu.handoff.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies the signature Genesys puts on every outbound webhook.
 *
 * <p>Format, confirmed from the Genesys team's Postman collection:
 *
 * <pre>
 *   X-Hub-Signature-256: sha256=&lt;base64 HMAC-SHA256(secret, rawBody)&gt;
 * </pre>
 *
 * <p>Note the encoding is <em>base64</em>, not hex. The same header name is
 * used by other platforms with a hex digest, so this is an easy thing to get
 * wrong, and the failure is silent — every request simply gets rejected.
 *
 * <p>The signature is computed over the raw request bytes. Parsing the JSON and
 * re-serialising it changes whitespace and key order, and the signature will
 * never match. The controller therefore takes the body as {@code byte[]}.
 *
 * <p>Anything between Genesys and this service must pass the body through
 * unchanged. If the API gateway reformats JSON, every signature fails.
 */
@Component
public class GenesysSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GenesysSignatureVerifier.class);

    private static final String HEADER = "X-Hub-Signature-256";
    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private final String secret;
    private final String previousSecret;

    public GenesysSignatureVerifier(
            @Value("${handoff.genesys.webhook-secret:}") String secret,
            @Value("${handoff.genesys.webhook-secret-previous:}") String previousSecret) {
        this.secret = secret;
        this.previousSecret = previousSecret;
    }

    /**
     * @param header the raw header value, including the {@code sha256=} prefix
     * @param body   the raw request bytes, exactly as received
     */
    public boolean isValid(String header, byte[] body) {
        if (secret == null || secret.isBlank()) {
            log.error("no Genesys webhook secret configured — rejecting all inbound webhooks");
            return false;
        }
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        String presented = header.substring(PREFIX.length());

        // A rotation window: the secret changes on the Genesys side and here at
        // slightly different moments. Accepting the previous secret for a
        // bounded period avoids a burst of rejected webhooks at the changeover.
        return matches(presented, body, secret)
                || (!previousSecret.isBlank() && matches(presented, body, previousSecret));
    }

    public static String headerName() {
        return HEADER;
    }

    private boolean matches(String presented, byte[] body, String key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body));

            // Constant-time comparison: a byte-by-byte equals leaks, through
            // timing, how much of the signature was correct.
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("could not compute webhook signature", e);
            return false;
        }
    }
}
