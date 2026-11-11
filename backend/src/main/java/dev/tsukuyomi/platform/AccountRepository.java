package dev.tsukuyomi.platform;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for accounts and their login sessions. No logic, only queries. */
@Repository
class AccountRepository {

	record Credentials(Account account, String passwordHash) {
	}

	private final JdbcTemplate jdbc;

	AccountRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return the new account, or empty if the email is already registered */
	Optional<Account> insert(String email, String passwordHash) {
		try {
			UUID id = jdbc.queryForObject(
					"insert into tsk_platform.account (email, password_hash) values (?, ?) returning id",
					UUID.class, email, passwordHash);
			return Optional.of(new Account(id, email));
		}
		catch (DuplicateKeyException e) {
			return Optional.empty();
		}
	}

	Optional<Credentials> findCredentials(String email) {
		return jdbc.query("select id, email, password_hash from tsk_platform.account where email = ?",
				(rs, n) -> new Credentials(new Account(rs.getObject("id", UUID.class), rs.getString("email")),
						rs.getString("password_hash")),
				email)
			.stream()
			.findFirst();
	}

	void insertSession(String tokenHash, UUID accountId, Instant expiresAt) {
		jdbc.update("insert into tsk_platform.session (token_hash, account_id, expires_at) values (?, ?, ?)",
				tokenHash, accountId, Timestamp.from(expiresAt));
	}

	Optional<Account> findBySession(String tokenHash) {
		return jdbc.query("""
				select a.id, a.email
				from tsk_platform.session s join tsk_platform.account a on a.id = s.account_id
				where s.token_hash = ? and s.expires_at > now()""",
				(rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email")), tokenHash)
			.stream()
			.findFirst();
	}

	void deleteSession(String tokenHash) {
		jdbc.update("delete from tsk_platform.session where token_hash = ?", tokenHash);
	}

	void deleteExpiredSessions() {
		jdbc.update("delete from tsk_platform.session where expires_at <= now()");
	}

}
