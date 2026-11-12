package dev.tsukuyomi.live;

import java.util.Map;

import dev.tsukuyomi.common.limit.ClientAddress;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the WebSocket endpoint and turns on the scheduled clean-up of the change log. */
@Configuration
@EnableWebSocket
@EnableScheduling
class LiveConfig implements WebSocketConfigurer {

	static final String ADDRESS = "clientAddress";

	private final LiveSocketHandler handler;

	private final ClientAddress addresses;

	LiveConfig(LiveSocketHandler handler, ClientAddress addresses) {
		this.handler = handler;
		this.addresses = addresses;
	}

	/**
	 * The WebSocket handshake is an HTTP request; this is the last moment the
	 * caller's address is available, so it is saved on the connection.
	 */
	private HandshakeInterceptor rememberAddress() {
		return new HandshakeInterceptor() {
			@Override
			public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
					WebSocketHandler socketHandler, Map<String, Object> attributes) {
				String socket = request.getRemoteAddress() == null ? "unknown"
						: request.getRemoteAddress().getAddress().getHostAddress();
				attributes.put(ADDRESS, addresses.resolve(request.getHeaders().getFirst("X-Forwarded-For"), socket));
				return true;
			}

			@Override
			public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
					WebSocketHandler socketHandler, Exception exception) {
			}
		};
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// Any website may connect, for the same reason the REST API allows every
		// origin (DECISIONS.md D30): there is no cookie to ride on. A connection
		// can do nothing until it sends a key in its "auth" message.
		registry.addHandler(handler, "/realtime/v1").addInterceptors(rememberAddress()).setAllowedOriginPatterns("*");
	}

}
