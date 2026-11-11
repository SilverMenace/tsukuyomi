package dev.tsukuyomi.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Random secrets and their hashes. */
public final class Tokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Tokens() {
	}

	/** {@code bytes} random bytes as lowercase hex (so the string is twice as long). */
	public static String randomHex(int bytes) {
		return HexFormat.of().formatHex(randomBytes(bytes));
	}

	public static byte[] randomBytes(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return buffer;
	}

	/**
	 * SHA-256 as hex. Used for secrets that are already long and random (session
	 * tokens, API keys). A fast hash is fine for those: nobody can guess 32 random
	 * bytes. Passwords are different (short, human-chosen) and use BCrypt instead.
	 */
	public static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // every JVM ships SHA-256
		}
	}

}
