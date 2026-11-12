package dev.tsukuyomi.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the WebSocket endpoint and turns on the scheduled clean-up of the change log. */
@Configuration
@EnableWebSocket
@EnableScheduling
class LiveConfig implements WebSocketConfigurer {

	private final LiveSocketHandler handler;

	LiveConfig(LiveSocketHandler handler) {
		this.handler = handler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// Any website may connect, for the same reason the REST API allows every
		// origin (DECISIONS.md D30): there is no cookie to ride on. A connection
		// can do nothing until it sends a key in its "auth" message.
		registry.addHandler(handler, "/realtime/v1").setAllowedOriginPatterns("*");
	}

}
