package dev.tsukuyomi.live;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Holds one database connection that does nothing but LISTEN. Postgres pushes
 * a notification down this connection each time a transaction that changed a
 * row commits (the trigger calls pg_notify; see V4__change_events.sql).
 *
 * The connection is opened when the first client subscribes and closed when
 * the last one leaves. An always-open connection that we keep checking would
 * stop the free database from ever going to sleep.
 *
 * If the connection breaks while clients are subscribed, it reconnects. The
 * notifications sent in between are lost, so the sink is told and asks every
 * client to reload.
 */
@Component
class ChangeListener {

	static final String CHANNEL = "tsk_changes";

	/** With no notification for this long, run a tiny query to find out whether the connection is still alive. */
	private static final int CHECK_EVERY_MS = 20_000;

	private static final Logger log = LoggerFactory.getLogger(ChangeListener.class);

	interface Sink {

		/** One notification payload. Called on the listener thread, in commit order. */
		void onChange(String payload);

		/** The connection was lost and is back; some notifications are missing. */
		void onFeedRestored();

	}

	private final JdbcConnectionDetails database;

	private Session current;

	ChangeListener(JdbcConnectionDetails database) {
		this.database = database;
	}

	/**
	 * Starts listening if not already. When this returns, LISTEN is active:
	 * every change that commits from now on will reach the sink.
	 */
	synchronized void start(Sink sink) throws SQLException {
		if (current != null) {
			return;
		}
		Session session = new Session(sink, connect());
		Thread.ofPlatform().name("live-listen").daemon().start(session);
		current = session;
	}

	synchronized void stop() {
		if (current != null) {
			current.stop();
			current = null;
		}
	}

	private Connection connect() throws SQLException {
		Properties properties = new Properties();
		properties.setProperty("user", database.getUsername());
		properties.setProperty("password", database.getPassword());
		properties.setProperty("connectTimeout", "20");
		properties.setProperty("tcpKeepAlive", "true");
		properties.setProperty("ApplicationName", "tsukuyomi-live");
		Connection connection = DriverManager.getConnection(database.getJdbcUrl(), properties);
		try (Statement statement = connection.createStatement()) {
			statement.execute("listen " + CHANNEL);
		}
		catch (SQLException e) {
			connection.close();
			throw e;
		}
		return connection;
	}

	/** One start-to-stop run. A new start creates a new Session, so an old thread can never serve a new run. */
	private final class Session implements Runnable {

		private final Sink sink;

		private volatile Connection connection;

		private volatile boolean running = true;

		Session(Sink sink, Connection connection) {
			this.sink = sink;
			this.connection = connection;
		}

		@Override
		public void run() {
			long pause = 1000;
			while (running) {
				try {
					if (connection == null) {
						connection = connect();
						pause = 1000;
						if (running) {
							sink.onFeedRestored();
						}
					}
					receive(connection);
				}
				catch (SQLException e) {
					if (running) {
						log.warn("live update listener lost its connection, reconnecting: {}", e.getMessage());
					}
				}
				close();
				if (running) {
					try {
						Thread.sleep(pause);
					}
					catch (InterruptedException e) {
						return;
					}
					pause = Math.min(pause * 2, 30_000);
				}
			}
			close();
		}

		private void receive(Connection connection) throws SQLException {
			PGConnection postgres = connection.unwrap(PGConnection.class);
			while (running) {
				PGNotification[] notifications = postgres.getNotifications(CHECK_EVERY_MS);
				if (notifications == null || notifications.length == 0) {
					try (Statement statement = connection.createStatement()) {
						statement.execute("select 1"); // throws if the connection is dead
					}
					continue;
				}
				for (PGNotification notification : notifications) {
					sink.onChange(notification.getParameter());
				}
			}
		}

		void stop() {
			running = false;
			close(); // also wakes the thread if it is waiting for a notification
		}

		private void close() {
			Connection open = connection;
			connection = null;
			if (open != null) {
				try {
					open.close();
				}
				catch (SQLException e) {
					// already broken; nothing to do
				}
			}
		}

	}

}
