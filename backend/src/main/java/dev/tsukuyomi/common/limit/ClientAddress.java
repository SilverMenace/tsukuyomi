package dev.tsukuyomi.common.limit;

import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out which machine a request really came from.
 *
 * Behind a hosting provider the TCP connection comes from the provider's
 * proxy, not from the visitor. Each proxy appends the address it received the
 * request from to the X-Forwarded-For header:
 *
 *   X-Forwarded-For: <whatever the client sent>, <visitor>, <proxy 1>
 *
 * The LEFT side of that list is written by the client and can be a lie. Only
 * the entries added by proxies we trust are reliable, and those are on the
 * RIGHT. So we count from the right: with N trusted proxies in front of us,
 * the visitor is the N-th entry from the right. Taking the first entry, as
 * many tutorials do, lets anyone dodge a rate limit by sending a fake header.
 */
@Component
public class ClientAddress {

	private final int trustedProxies;

	ClientAddress(@Value("${tsukuyomi.rate-limit.trusted-proxies:0}") int trustedProxies) {
		this.trustedProxies = trustedProxies;
	}

	public String of(HttpServletRequest request) {
		// A request can carry the header several times. HTTP says that equals one
		// header with the values joined by commas, so join them before counting;
		// reading only the first line would let a client put its own line first.
		String forwardedFor = String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
		return resolve(forwardedFor.isEmpty() ? null : forwardedFor, request.getRemoteAddr());
	}

	/** @param forwardedFor the X-Forwarded-For header, or null */
	public String resolve(String forwardedFor, String socketAddress) {
		if (trustedProxies <= 0 || forwardedFor == null) {
			return socketAddress;
		}
		String[] entries = forwardedFor.split(",");
		int index = entries.length - trustedProxies;
		if (index < 0) {
			return socketAddress; // fewer proxies than configured: do not trust the header at all
		}
		String address = entries[index].trim();
		// Keep keys small whatever the header contains.
		return address.isEmpty() || address.length() > 45 ? socketAddress : address;
	}

}
