package dev.tsukuyomi.schema;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Hands out the current catalog of a project, cached in memory.
 *
 * Every data request needs the catalog, so reading it from the database each
 * time would double the number of queries. A migration evicts the project's
 * entry. This is correct while there is one running instance (see README
 * limits); with several instances they would need to tell each other.
 */
@Service
public class CatalogService {

	private final Map<UUID, Catalog> cache = new ConcurrentHashMap<>();

	private final CatalogRepository repository;

	CatalogService(CatalogRepository repository) {
		this.repository = repository;
	}

	public Catalog get(UUID projectId) {
		return cache.computeIfAbsent(projectId, repository::load);
	}

	public void evict(UUID projectId) {
		cache.remove(projectId);
	}

}
