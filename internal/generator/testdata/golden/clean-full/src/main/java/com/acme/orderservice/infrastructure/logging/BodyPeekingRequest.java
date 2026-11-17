package com.acme.orderservice.infrastructure.logging;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Lets the logging filter see the first bytes of the request body before the
 * request is handled, while still delivering the complete body to the
 * controller: the peeked prefix is replayed, then the untouched remainder of
 * the original stream.
 *
 * <p>Memory stays bounded by {@code limit}; larger bodies are streamed through
 * and only the prefix is logged.</p>
 */
public class BodyPeekingRequest extends HttpServletRequestWrapper {

	private final byte[] head;

	private final int limit;

	private final boolean truncated;

	private final ServletInputStream rest;

	public BodyPeekingRequest(HttpServletRequest request, int limit) throws IOException {
		super(request);
		this.limit = limit;
		ServletInputStream original = request.getInputStream();
		byte[] read = original.readNBytes(limit + 1);
		this.truncated = read.length > limit;
		this.head = read;
		this.rest = original;
	}

	public boolean truncated() {
		return truncated;
	}

	/** Bytes to render in the log, capped at the configured limit. */
	public byte[] loggable() {
		return truncated ? Arrays.copyOf(head, limit) : head;
	}

	@Override
	public ServletInputStream getInputStream() {
		return new ServletInputStream() {

			private final ByteArrayInputStream prefix = new ByteArrayInputStream(head);

			@Override
			public int read() throws IOException {
				int next = prefix.read();
				return next != -1 ? next : rest.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				int read = prefix.read(buffer, offset, length);
				return read > 0 ? read : rest.read(buffer, offset, length);
			}

			@Override
			public boolean isFinished() {
				return prefix.available() == 0 && rest.isFinished();
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException("async reads are not supported");
			}

		};
	}

	@Override
	public BufferedReader getReader() {
		return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
	}

	private Charset charset() {
		String encoding = getCharacterEncoding();
		if (encoding == null || encoding.isBlank()) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(encoding);
		}
		catch (RuntimeException ex) {
			return StandardCharsets.UTF_8;
		}
	}

}
