package dev.tsukuyomi.platform;

import java.util.UUID;

/** A dashboard user: someone who owns projects. Not an end user of a project. */
public record Account(UUID id, String email) {
}
