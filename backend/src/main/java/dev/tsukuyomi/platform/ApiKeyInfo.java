package dev.tsukuyomi.platform;

import java.time.Instant;

/** What we can still show about a key after creation: never the key itself. */
public record ApiKeyInfo(String kind, String prefix, Instant createdAt) {
}
