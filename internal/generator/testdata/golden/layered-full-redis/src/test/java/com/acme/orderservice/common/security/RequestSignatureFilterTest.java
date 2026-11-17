package com.acme.orderservice.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class RequestSignatureFilterTest {

	private static final long NOW = 1795430400123L;

	private static final String SECRET = "test-secret";

	private static final String BODY = "{\"name\":\"Tee\"}";

	private final RequestSignatureProperties properties = properties(false, DataSize.ofMegabytes(1));

	private final RequestSigner signer = new RequestSigner(properties);

	private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

	@Test
	void acceptsAValidSignatureAndPreservesTheBody() throws Exception {
		MockHttpServletRequest request = signedRequest(BODY, NOW);
		MockHttpServletResponse response = new MockHttpServletResponse();

		RequestSignatureFilter filter = filter(properties, (id, ttl) -> true);
		filter.doFilter(request, response, echoBody());

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).isEqualTo(BODY);
	}

	@Test
	void rejectsMissingRequestId() throws Exception {
		MockHttpServletRequest request = signedRequest(BODY, NOW);
		request.removeHeader(RequestSignatureFilter.REQUEST_ID_HEADER);

		assertRejected(request, properties, 401, "Missing header X-Request-Id.");
	}

	@Test
	void rejectsAnInvalidRequestId() throws Exception {
		MockHttpServletRequest request = signedRequest(BODY, NOW);
		request.removeHeader(RequestSignatureFilter.REQUEST_ID_HEADER);
		request.addHeader(RequestSignatureFilter.REQUEST_ID_HEADER, "bad id!");

		assertRejected(request, properties, 401, "Invalid request id format.");
	}

	@Test
	void rejectsAStaleTimestamp() throws Exception {
		MockHttpServletRequest request = signedRequest(BODY, NOW - Duration.ofMinutes(10).toMillis());

		assertRejected(request, properties, 401, "Request timestamp is outside the allowed window.");
	}

	@Test
	void rejectsAFutureTimestamp() throws Exception {
		MockHttpServletRequest request = signedRequest(BODY, NOW + Duration.ofMinutes(10).toMillis());

		assertRejected(request, properties, 401, "Request timestamp is outside the allowed window.");
	}

	@Test
	void rejectsATamperedBody() throws Exception {
		MockHttpServletRequest request = signedRequest(BODY, NOW);
		request.setContent("{\"name\":\"Eve\"}".getBytes(StandardCharsets.UTF_8));

		assertRejected(request, properties, 401, "Invalid request signature.");
	}

	@Test
	void rejectsAMissingSignature() throws Exception {
		MockHttpServletRequest request = signedRequest(BODY, NOW);
		request.removeHeader(RequestSignatureFilter.SIGNATURE_HEADER);

		assertRejected(request, properties, 401, "Missing header X-Request-Signature.");
	}

	@Test
	void rejectsADuplicateRequestId() throws Exception {
		RequestSignatureProperties withCheck = properties(true, DataSize.ofMegabytes(1));

		assertRejected(signedRequest(BODY, NOW), withCheck, 409, "Duplicate request id.", (id, ttl) -> false);
	}

	@Test
	void rejectsWhenTheStoreIsUnavailable() throws Exception {
		RequestSignatureProperties withCheck = properties(true, DataSize.ofMegabytes(1));

		assertRejected(signedRequest(BODY, NOW), withCheck, 503, "Request id store unavailable.", (id, ttl) -> {
			throw new IllegalStateException("redis down");
		});
	}

	@Test
	void rejectsBodiesOverTheLimit() throws Exception {
		RequestSignatureProperties small = properties(false, DataSize.ofBytes(4));

		assertRejected(signedRequest(BODY, NOW), small, 413, "Request body is too large to sign.");
	}

	@Test
	void skipsExcludedAndUnprotectedPaths() throws Exception {
		MockHttpServletResponse health = new MockHttpServletResponse();
		MockHttpServletRequest healthRequest = new MockHttpServletRequest("GET", "/actuator/health");
		filter(properties, (id, ttl) -> true).doFilter(healthRequest, health, echoBody());
		assertThat(health.getStatus()).isEqualTo(200);

		MockHttpServletResponse other = new MockHttpServletResponse();
		MockHttpServletRequest otherRequest = new MockHttpServletRequest("GET", "/public/info");
		filter(properties, (id, ttl) -> true).doFilter(otherRequest, other, echoBody());
		assertThat(other.getStatus()).isEqualTo(200);
	}

	private MockHttpServletRequest signedRequest(String body, long timestamp) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));

		String requestId = "req-1";
		String timestampText = Long.toString(timestamp);
		request.addHeader(RequestSignatureFilter.REQUEST_ID_HEADER, requestId);
		request.addHeader(RequestSignatureFilter.TIMESTAMP_HEADER, timestampText);
		request.addHeader(RequestSignatureFilter.SIGNATURE_HEADER,
			signer.sign(requestId, timestampText, body.getBytes(StandardCharsets.UTF_8)));
		return request;
	}

	private void assertRejected(MockHttpServletRequest request, RequestSignatureProperties props, int status,
			String message) throws Exception {
		assertRejected(request, props, status, message, (id, ttl) -> true);
	}

	private void assertRejected(MockHttpServletRequest request, RequestSignatureProperties props, int status,
			String message, RequestIdStore store) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(props, store).doFilter(request, response, echoBody());

		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getContentAsString()).contains(message);
		assertThat(response.getContentAsString()).contains("\"status\":\"fail\"");
	}

	private RequestSignatureFilter filter(RequestSignatureProperties props, RequestIdStore store) {
		return new RequestSignatureFilter(props, new RequestSigner(props), store, new ObjectMapper(), clock);
	}

	private static RequestSignatureProperties properties(boolean requestIdCheck, DataSize maxBody) {
		return new RequestSignatureProperties(true, SECRET, Duration.ofMinutes(5), maxBody,
			List.of("/api/**"), List.of("/actuator/**"),
			new RequestSignatureProperties.RequestIdCheck(requestIdCheck, null));
	}

	private static FilterChain echoBody() {
		return (request, response) -> {
			try {
				byte[] body = request.getInputStream().readAllBytes();
				response.setContentType("application/json");
				response.getOutputStream().write(body);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		};
	}

}
