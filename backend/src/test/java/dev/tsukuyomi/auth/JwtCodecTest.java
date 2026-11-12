package dev.tsukuyomi.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import dev.tsukuyomi.auth.JwtCodec.Claims;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtCodecTest {

	private final JwtCodec jwt = new JwtCodec(new ObjectMapper());

	private final byte[] secret = "a-32-byte-secret-for-unit-tests!".getBytes(StandardCharsets.UTF_8);

	private final UUID user = UUID.randomUUID();

	private final UUID project = UUID.randomUUID();

	private final Instant now = Instant.parse("2026-11-12T10:00:00Z");

	private String validToken() {
		return jwt.sign(secret, new Claims(user, project, now.plusSeconds(3600)));
	}

	private static String b64(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void roundTrip() {
		Claims claims = jwt.verify(secret, project, validToken(), now).orElseThrow();
		assertThat(claims.userId()).isEqualTo(user);
		assertThat(claims.projectId()).isEqualTo(project);
		assertThat(validToken().split("\\.")).hasSize(3);
	}

	@Test
	void rejectsExpiredToken() {
		assertThat(jwt.verify(secret, project, validToken(), now.plusSeconds(3599))).isPresent();
		assertThat(jwt.verify(secret, project, validToken(), now.plusSeconds(3600))).isEmpty();
	}

	@Test
	void rejectsTokenSignedWithAnotherSecret() {
		byte[] other = "another-32-byte-secret-for-tests".getBytes(StandardCharsets.UTF_8);
		assertThat(jwt.verify(other, project, validToken(), now)).isEmpty();
	}

	@Test
	void rejectsTokenIssuedForAnotherProject() {
		// Even with the same secret, the project id inside the token must match.
		assertThat(jwt.verify(secret, UUID.randomUUID(), validToken(), now)).isEmpty();
	}

	@Test
	void rejectsModifiedPayload() {
		String[] parts = validToken().split("\\.");
		String forged = b64("{\"sub\":\"" + UUID.randomUUID() + "\",\"pid\":\"" + project + "\",\"exp\":9999999999}");
		assertThat(jwt.verify(secret, project, parts[0] + "." + forged + "." + parts[2], now)).isEmpty();
	}

	@Test
	void rejectsAlgNone() {
		// The classic attack: claim that no signature is needed.
		String payload = validToken().split("\\.")[1];
		String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
		assertThat(jwt.verify(secret, project, header + "." + payload + ".", now)).isEmpty();
		assertThat(jwt.verify(secret, project, header + "." + payload, now)).isEmpty();
	}

	@Test
	void rejectsGarbageWithoutThrowing() {
		for (String token : new String[] { "", ".", "..", "a.b.c", "not a token", validToken() + "x",
				validToken().substring(0, 20), "....", validToken().replace('.', '!'), "\u0000" }) {
			assertThat(jwt.verify(secret, project, token, now)).as(token).isEmpty();
		}
	}

}
