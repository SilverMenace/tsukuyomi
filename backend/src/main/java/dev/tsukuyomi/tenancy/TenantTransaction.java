package dev.tsukuyomi.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.tsukuyomi.common.Tokens;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.stereotype.Component;

/**
 * The only door to tenant data.
 *
 * It owns a private connection pool that logs in as the role "tsk_api". That
 * role has no privileges at all: on its own it cannot read one row of any
 * project. To do anything, a transaction must first switch into one project's
 * role, and this class is the only place that happens.
 *
 * Every piece of per-request state is set with SET LOCAL, which Postgres
 * discards at commit or rollback. So when a connection goes back to the pool
 * it is a blank "tsk_api" connection again; nothing from this request can be
 * seen by the next one. (See DECISIONS.md D3 and D4.)
 */
@Component
public class TenantTransaction implements DisposableBean {

	public static final String LOGIN_ROLE = "tsk_api";

	private static final Pattern SAFE_PASSWORD = Pattern.compile("^[A-Za-z0-9]{24,128}$");

	private final HikariDataSource pool;

	private final int statementTimeoutMs;

	TenantTransaction(JdbcTemplate ownerJdbc, JdbcConnectionDetails database,
			@Value("${tsukuyomi.api-role.password:}") String configuredPassword,
			@Value("${tsukuyomi.data.pool-size:5}") int poolSize,
			@Value("${tsukuyomi.data.statement-timeout-ms:3000}") int statementTimeoutMs) {
		this.statementTimeoutMs = statementTimeoutMs;
		String password = configuredPassword.isBlank() ? Tokens.randomHex(24) : configuredPassword;
		ensureLoginRole(ownerJdbc, password);

		HikariConfig config = new HikariConfig();
		config.setPoolName("tenant-data");
		config.setJdbcUrl(database.getJdbcUrl());
		config.setUsername(LOGIN_ROLE);
		config.setPassword(password);
		config.setMaximumPoolSize(poolSize);
		config.setMinimumIdle(0);
		config.setIdleTimeout(60_000);
		config.setConnectionTimeout(20_000);
		config.setAutoCommit(false);
		// Do not connect at startup; the first request opens the first connection.
		config.setInitializationFailTimeout(-1);
		this.pool = new HikariDataSource(config);
	}

	/**
	 * Runs {@code work} in one transaction as {@code identity}. Commits if it
	 * returns, rolls back if it throws.
	 */
	public <T> T execute(TenantIdentity identity, TenantWork<T> work) {
		try (Connection connection = pool.getConnection()) {
			try {
				enter(connection, identity);
				T result = work.run(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
				connection.commit();
				return result;
			}
			catch (Throwable t) {
				connection.rollback();
				throw t;
			}
		}
		catch (SQLException e) {
			throw new SQLErrorCodeSQLExceptionTranslator().translate("tenant transaction", null, e);
		}
	}

	private void enter(Connection connection, TenantIdentity identity) throws SQLException {
		ProjectRef project = identity.project();
		try (Statement statement = connection.createStatement()) {
			// Identifiers cannot be bind parameters. Both names come from ProjectRef,
			// which only accepts the exact pattern p_<12 hex>, so they are safe to embed.
			statement.execute("set local role \"" + project.roleName(identity.role()) + "\"");
			statement.execute("set local search_path = \"" + project.schemaName() + "\"");
			statement.execute("set local statement_timeout = " + statementTimeoutMs);
		}
		// The user id is a value, so it travels as a bind parameter.
		try (PreparedStatement statement = connection
			.prepareStatement("select set_config('tsk.user_id', ?, true)")) {
			statement.setString(1, identity.userId() == null ? "" : identity.userId().toString());
			statement.execute();
		}
	}

	/**
	 * Creates the login role on first start, and sets its password on every
	 * start so the database always matches our configuration. If no password is
	 * configured we invent a fresh one each start, which is fine while there is
	 * exactly one running instance.
	 */
	private static void ensureLoginRole(JdbcTemplate ownerJdbc, String password) {
		if (!SAFE_PASSWORD.matcher(password).matches()) {
			throw new IllegalStateException("tsukuyomi.api-role.password must be 24-128 letters and digits");
		}
		Integer exists = ownerJdbc.queryForObject("select count(*) from pg_roles where rolname = ?", Integer.class,
				LOGIN_ROLE);
		// Utility statements cannot take bind parameters; the password was checked above.
		if (exists != null && exists == 0) {
			ownerJdbc.execute("create role " + LOGIN_ROLE + " login noinherit password '" + password + "'");
		}
		else {
			ownerJdbc.execute("alter role " + LOGIN_ROLE + " password '" + password + "'");
		}
	}

	@Override
	public void destroy() {
		pool.close();
	}

	/** Work to run inside a tenant transaction. The JdbcTemplate is bound to that one connection. */
	@FunctionalInterface
	public interface TenantWork<T> {

		T run(JdbcTemplate jdbc);

	}

}
