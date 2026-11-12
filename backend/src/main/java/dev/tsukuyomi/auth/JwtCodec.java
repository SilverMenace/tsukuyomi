package dev.tsukuyomi.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Creates and checks JSON Web Tokens (JWT), written by hand so every step is visible.
 *
 * A token is three base64url parts joined by dots:
 *
 *   header  . payload                         . signature
 *   {"alg"} . {"sub": user, "pid": project,   . HMAC-SHA256(secret, header + "." + payload)
 *              "exp": expiry}
 *
 * Anyone can READ the payload (it is only encoded, not encrypted). What they
 * cannot do is CHANGE it: the signature is computed with the project's secret
 * key, so a modified payload no longer matches its signature. That lets the
 * server trust "this request is from user X" without a database lookup.
 *
 * Each project has its own secret, and the project id is also inside the
 * token, so a token issued by one project is worthless in another.
 */
@Component
public class JwtCodec {

	/** What a valid token says. */
	public record Claims(UUID userId, UUID projectId, Instant expiresAt) {
	}

	private static final String ALGORITHM = "HmacSHA256";

	/** Fixed header. We only ever issue and accept HS256. */
	private static final String HEADER = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

	private final ObjectMapper json;

	JwtCodec(ObjectMapper json) {
		this.json = json;
	}

	public String sign(byte[] secret, Claims claims) {
		String payload = encode(json.writeValueAsBytes(Map.of("sub", claims.userId().toString(), "pid",
				claims.projectId().toString(), "exp", claims.expiresAt().getEpochSecond())));
		String signed = HEADER + "." + payload;
		return signed + "." + encode(hmac(secret, signed));
	}

	/**
	 * @return the claims if the token is genuine, unexpired and for this
	 * project; empty for anything else. It never throws, because the input is
	 * attacker-controlled.
	 */
	public Optional<Claims> verify(byte[] secret, UUID expectedProject, String token, Instant now) {
		try {
			String[] parts = token.split("\\.", -1);
			if (parts.length != 3) {
				return Optional.empty();
			}
			// 1. The header must be exactly ours. This rejects the classic attack of
			//    sending {"alg":"none"} to skip signature checking: we never look at
			//    the token to decide HOW to verify it.
			if (!parts[0].equals(HEADER)) {
				return Optional.empty();
			}
			// 2. Recompute the signature and compare in constant time (a normal
			//    equals() returns sooner the earlier the first difference is, which
			//    leaks how much of a forged signature was right).
			byte[] expected = hmac(secret, parts[0] + "." + parts[1]);
			byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
			if (!MessageDigest.isEqual(expected, actual)) {
				return Optional.empty();
			}
			// 3. Only now is the payload trustworthy enough to read.
			JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
			Claims claims = new Claims(UUID.fromString(payload.get("sub").asString()),
					UUID.fromString(payload.get("pid").asString()), Instant.ofEpochSecond(payload.get("exp").asLong()));
			if (!claims.projectId().equals(expectedProject) || !claims.expiresAt().isAfter(now)) {
				return Optional.empty();
			}
			return Optional.of(claims);
		}
		catch (RuntimeException malformed) {
			return Optional.empty();
		}
	}

	private static byte[] hmac(byte[] secret, String data) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		}
		catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e); // every JVM ships HmacSHA256
		}
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
