package dev.tsukuyomi.common.limit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * A request whose body can be read only up to a limit. Reading past it throws,
 * which the caller sees as an unreadable body (400). This catches bodies sent
 * without a Content-Length, which the declared-length check cannot.
 */
final class LimitedBodyRequest extends HttpServletRequestWrapper {

	private final long maxBytes;

	LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
		super(request);
		this.maxBytes = maxBytes;
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		ServletInputStream body = super.getInputStream();
		return new ServletInputStream() {
			private long seen;

			private void count(long bytes) throws IOException {
				seen += bytes;
				if (seen > maxBytes) {
					throw new IOException("request body is larger than " + maxBytes + " bytes");
				}
			}

			@Override
			public int read() throws IOException {
				int b = body.read();
				if (b >= 0) {
					count(1);
				}
				return b;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				int n = body.read(buffer, offset, length);
				if (n > 0) {
					count(n);
				}
				return n;
			}

			@Override
			public boolean isFinished() {
				return body.isFinished();
			}

			@Override
			public boolean isReady() {
				return body.isReady();
			}

			@Override
			public void setReadListener(ReadListener listener) {
				body.setReadListener(listener);
			}
		};
	}

	@Override
	public BufferedReader getReader() throws IOException {
		String encoding = getCharacterEncoding();
		Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
		return new BufferedReader(new InputStreamReader(getInputStream(), charset));
	}

}
