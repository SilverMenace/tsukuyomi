import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Properties;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * Spike: does the hosted Postgres allow the isolation design in PLAN.md section 4 and 7?
 *
 * It is a throwaway experiment, kept in the repo because DECISIONS.md refers to its result.
 * It creates two temporary roles and one temporary schema, checks each assumption, and
 * removes everything again.
 *
 * Run (from the repo root, with DATABASE_URL exported from .env):
 *   java -cp backend/target/spike-lib/postgresql-*.jar scripts/spike/IsolationSpike.java
 *
 * Assumptions checked:
 *   1. We can create roles with plain SQL.
 *   2. A NOINHERIT login role with no grants cannot read a tenant table.
 *   3. That role can "SET LOCAL ROLE" into a tenant role inside a transaction.
 *   4. Row-level security then filters rows using a transaction-local setting.
 *   5. After the transaction ends, the role and the setting are gone (safe for pooling).
 *   6. A row trigger can NOTIFY and a second connection receives it via LISTEN.
 */
public class IsolationSpike {

	static int failures = 0;

	public static void main(String[] args) throws Exception {
		Target owner = Target.parse(require("DATABASE_URL"));
		String apiPassword = randomHex(24);
		// Unique per run. A connection pooler can keep a server session that was
		// authenticated as an earlier role with the same name; after that role is
		// dropped and recreated, the stale session has none of the new grants.
		String apiRole = "tsk_spike_api_" + randomHex(4);

		try (Connection admin = owner.connect()) {
			try (Statement st = admin.createStatement()) {
				info("server", scalar(st, "select current_setting('server_version')"));
				info("owner attrs", scalar(st, """
					select 'createrole=' || rolcreaterole || ' bypassrls=' || rolbypassrls || ' superuser=' || rolsuper
					from pg_roles where rolname = current_user"""));

				cleanup(st); // in case a previous run died half-way

				// 1. roles
				st.execute("create role " + apiRole + " login noinherit password '" + apiPassword + "'");
				st.execute("create role tsk_spike_tenant nologin");
				st.execute("grant tsk_spike_tenant to " + apiRole + " with inherit false, set true");
				check("1. create roles with SQL", true);

				// tenant schema, table, rows, RLS
				st.execute("create schema tsk_spike");
				st.execute("""
					create table tsk_spike.notes (
					  id int primary key, owner_id uuid not null, body text not null)""");
				st.execute("""
					insert into tsk_spike.notes values
					  (1, '00000000-0000-0000-0000-00000000000a', 'belongs to A'),
					  (2, '00000000-0000-0000-0000-00000000000b', 'belongs to B')""");
				st.execute("alter table tsk_spike.notes enable row level security");
				st.execute("alter table tsk_spike.notes force row level security");
				st.execute("""
					create policy read_owner on tsk_spike.notes for select to tsk_spike_tenant
					  using (owner_id = nullif(current_setting('tsk.user_id', true), '')::uuid)""");
				st.execute("""
					create policy insert_owner on tsk_spike.notes for insert to tsk_spike_tenant
					  with check (owner_id = nullif(current_setting('tsk.user_id', true), '')::uuid)""");
				st.execute("grant usage on schema tsk_spike to tsk_spike_tenant");
				st.execute("grant select, insert on tsk_spike.notes to tsk_spike_tenant");

				// 6 (setup). trigger that notifies
				st.execute("""
					create function tsk_spike.notify_change() returns trigger
					language plpgsql security definer set search_path = pg_catalog as $$
					begin
					  perform pg_notify('tsk_spike_changes', new.id::text);
					  return null;
					end $$""");
				st.execute("""
					create trigger notes_changed after insert on tsk_spike.notes
					  for each row execute function tsk_spike.notify_change()""");
			}

			Target api = owner.withCredentials(apiRole, apiPassword);
			try (Connection data = api.connect(); Connection listener = owner.connect()) {
				try (Statement ls = listener.createStatement()) {
					ls.execute("listen tsk_spike_changes");
				}

				// 2. no privileges on its own
				boolean denied = false;
				try (Statement st = data.createStatement()) {
					st.executeQuery("select count(*) from tsk_spike.notes");
				} catch (SQLException e) {
					denied = "42501".equals(e.getSQLState()); // insufficient_privilege
				}
				check("2. login role alone is denied", denied);

				// 3 + 4. switch role inside a transaction, RLS filters by setting
				data.setAutoCommit(false);
				try (Statement st = data.createStatement()) {
					st.execute("set local role tsk_spike_tenant");
					st.execute("select set_config('tsk.user_id', '00000000-0000-0000-0000-00000000000a', true)");
					check("3. SET LOCAL ROLE works", "tsk_spike_tenant".equals(scalar(st, "select current_user")));
					check("4. RLS shows only A's row",
						"1|belongs to A".equals(scalar(st, "select count(*) || '|' || min(body) from tsk_spike.notes")));

					// write as A for someone else must be rejected by WITH CHECK
					boolean rejected = false;
					st.execute("savepoint s");
					try {
						st.execute("insert into tsk_spike.notes values (3, '00000000-0000-0000-0000-00000000000b', 'forged')");
					} catch (SQLException e) {
						rejected = "42501".equals(e.getSQLState());
						st.execute("rollback to savepoint s");
					}
					check("4b. RLS rejects insert for another owner", rejected);

					st.execute("insert into tsk_spike.notes values (4, '00000000-0000-0000-0000-00000000000a', 'new from A')");
				}
				data.commit();

				// 5. nothing leaks past the transaction
				data.setAutoCommit(true);
				try (Statement st = data.createStatement()) {
					check("5. role resets after commit", apiRole.equals(scalar(st, "select current_user")));
					String leftover = scalar(st, "select coalesce(current_setting('tsk.user_id', true), '')");
					check("5b. setting resets after commit", leftover.isEmpty());
				}

				// 6. the insert above fired the trigger; did the listener hear it?
				PGNotification[] got = listener.unwrap(PGConnection.class).getNotifications(10_000);
				boolean heard = got != null && got.length == 1 && "4".equals(got[0].getParameter());
				check("6. LISTEN receives trigger NOTIFY", heard);
			}

			// Optional: same role switch through the connection pooler (transaction mode).
			String pooledUrl = System.getenv("DATABASE_URL_POOLED");
			if (pooledUrl != null && !pooledUrl.isBlank()) {
				Target pooled = Target.parse(pooledUrl).withCredentials(apiRole, apiPassword);
				try (Connection c = pooled.connect()) {
					c.setAutoCommit(false);
					try (Statement st = c.createStatement()) {
						st.execute("set local role tsk_spike_tenant");
						st.execute("select set_config('tsk.user_id', '00000000-0000-0000-0000-00000000000b', true)");
						check("7. role switch through pooler", "1".equals(scalar(st, "select count(*) from tsk_spike.notes")));
					}
					c.rollback();
				} catch (SQLException e) {
					check("7. role switch through pooler (" + e.getSQLState() + ": " + firstLine(e) + ")", false);
				}
			}

			try (Statement st = admin.createStatement()) {
				cleanup(st);
				info("cleanup", "temporary roles and schema removed");
			}
		}

		System.out.println(failures == 0 ? "\nRESULT: all assumptions hold" : "\nRESULT: " + failures + " assumption(s) FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	static void cleanup(Statement st) throws SQLException {
		st.execute("drop schema if exists tsk_spike cascade");
		try (ResultSet rs = st.executeQuery("select rolname from pg_roles where rolname like 'tsk\\_spike\\_%'")) {
			java.util.List<String> roles = new java.util.ArrayList<>();
			while (rs.next()) roles.add(rs.getString(1));
			// The roles own nothing, and dropping the schema removed every grant they
			// had, so a plain DROP ROLE is enough.
			for (String role : roles) st.execute("drop role " + role);
		}
	}

	static String scalar(Statement st, String sql) throws SQLException {
		try (ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getString(1);
		}
	}

	static void check(String name, boolean ok) {
		if (!ok) failures++;
		System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", name);
	}

	static void info(String key, String value) {
		System.out.printf("  %-12s %s%n", key + ":", value);
	}

	static String firstLine(SQLException e) {
		return e.getMessage().lines().findFirst().orElse("");
	}

	static String require(String name) {
		String v = System.getenv(name);
		if (v == null || v.isBlank()) throw new IllegalStateException(name + " is not set");
		return v;
	}

	static String randomHex(int bytes) {
		byte[] b = new byte[bytes];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	/** A postgresql://user:pass@host/db?params URL split into what JDBC needs. Never printed. */
	record Target(String jdbcUrl, String user, String password) {

		static Target parse(String url) {
			URI u = URI.create(url.trim());
			String[] userInfo = u.getRawUserInfo().split(":", 2);
			String jdbc = "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "")
				+ u.getRawPath() + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
			return new Target(jdbc, decode(userInfo[0]), decode(userInfo[1]));
		}

		Target withCredentials(String user, String password) {
			return new Target(jdbcUrl, user, password);
		}

		Connection connect() throws SQLException {
			Properties p = new Properties();
			p.setProperty("user", user);
			p.setProperty("password", password);
			return DriverManager.getConnection(jdbcUrl, p);
		}

		static String decode(String s) {
			return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
		}
	}
}
