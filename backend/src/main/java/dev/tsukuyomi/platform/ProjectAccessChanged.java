package dev.tsukuyomi.platform;

import java.util.UUID;

/**
 * Published after a project's API key was replaced or the project was deleted.
 * Anything that was let in with the old credentials and is still connected
 * (live-update WebSockets) must be shown the door.
 */
public record ProjectAccessChanged(UUID projectId) {
}
