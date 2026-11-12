package dev.tsukuyomi.platform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantRole;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

	/** What an API key resolves to. */
	public record KeyOwner(ProjectRef project, TenantRole role, byte[] jwtSecret) {
	}

	private static final RowMapper<Project> PROJECT = (rs, n) -> new Project(rs.getObject("id", UUID.class),
			rs.getObject("account_id", UUID.class), rs.getString("name"), rs.getString("schema_name"),
			rs.getInt("schema_version"), rs.getTimestamp("created_at").toInstant());

	private final JdbcTemplate jdbc;

	ProjectRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	Project insert(UUID accountId, String name, String schemaName, byte[] jwtSecret) {
		return jdbc.queryForObject("""
				insert into tsk_platform.project (account_id, name, schema_name, jwt_secret)
				values (?, ?, ?, ?) returning *""", PROJECT, accountId, name, schemaName, jwtSecret);
	}

	List<Project> findByAccount(UUID accountId) {
		return jdbc.query("select * from tsk_platform.project where account_id = ? order by created_at", PROJECT,
				accountId);
	}

	/**
	 * Locks the account row until the surrounding transaction ends. Two
	 * "create project" requests for the same account then run one after the
	 * other, so both cannot pass the quota check at the same moment.
	 */
	void lockAccount(UUID accountId) {
		jdbc.queryForObject("select id from tsk_platform.account where id = ? for update", UUID.class, accountId);
	}

	/** Locks the project row until the surrounding transaction ends. */
	public void lockProject(UUID projectId) {
		jdbc.queryForObject("select id from tsk_platform.project where id = ? for update", UUID.class, projectId);
	}

	public byte[] findJwtSecret(UUID projectId) {
		return jdbc.queryForObject("select jwt_secret from tsk_platform.project where id = ?", byte[].class, projectId);
	}

	int countByAccount(UUID accountId) {
		return jdbc.queryForObject("select count(*) from tsk_platform.project where account_id = ?", Integer.class,
				accountId);
	}

	boolean nameExists(UUID accountId, String name) {
		return jdbc.queryForObject("select count(*) from tsk_platform.project where account_id = ? and name = ?",
				Integer.class, accountId, name) > 0;
	}

	/** Looks a project up only if it belongs to the given account. */
	public Optional<Project> findOwned(UUID projectId, UUID accountId) {
		return jdbc.query("select * from tsk_platform.project where id = ? and account_id = ?", PROJECT, projectId,
				accountId)
			.stream()
			.findFirst();
	}

	void delete(UUID projectId) {
		jdbc.update("delete from tsk_platform.project where id = ?", projectId);
	}

	void upsertKey(UUID projectId, String kind, String keyHash, String prefix) {
		jdbc.update("""
				insert into tsk_platform.api_key (project_id, kind, key_hash, prefix) values (?, ?, ?, ?)
				on conflict (project_id, kind)
				do update set key_hash = excluded.key_hash, prefix = excluded.prefix, created_at = now()""",
				projectId, kind, keyHash, prefix);
	}

	List<ApiKeyInfo> findKeys(UUID projectId) {
		return jdbc.query("select kind, prefix, created_at from tsk_platform.api_key where project_id = ? order by kind",
				(rs, n) -> new ApiKeyInfo(rs.getString("kind"), rs.getString("prefix"),
						rs.getTimestamp("created_at").toInstant()),
				projectId);
	}

	public Optional<KeyOwner> findKeyOwner(String keyHash) {
		return jdbc.query("""
				select p.id, p.schema_name, p.jwt_secret, k.kind
				from tsk_platform.api_key k join tsk_platform.project p on p.id = k.project_id
				where k.key_hash = ?""",
				(rs, n) -> new KeyOwner(new ProjectRef(rs.getObject("id", UUID.class), rs.getString("schema_name")),
						"service".equals(rs.getString("kind")) ? TenantRole.SERVICE : TenantRole.ANON,
						rs.getBytes("jwt_secret")),
				keyHash)
			.stream()
			.findFirst();
	}

}
