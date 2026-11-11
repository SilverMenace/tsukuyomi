package dev.tsukuyomi.platform;

import java.time.Instant;
import java.util.UUID;

import dev.tsukuyomi.tenancy.ProjectRef;

public record Project(UUID id, UUID accountId, String name, String schemaName, int schemaVersion,
		Instant createdAt) {

	public ProjectRef ref() {
		return new ProjectRef(id, schemaName);
	}

}
