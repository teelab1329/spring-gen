package com.acme.orderservice.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class HttpLoggingFilterTest {

	private Logger logger;

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachAppender() {
		logger = (Logger) LoggerFactory.getLogger(HttpLoggingFilter.class);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
	}

	@Test
	void logsRequestAndResponseWithMaskedHeaders() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hello");
		request.setQueryString("name=Tee");
		request.addHeader("Authorization", "secret-token");
		request.addHeader("Accept", "application/json");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(properties()).doFilter(request, response, (req, res) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) res;
			httpResponse.setContentType("application/json");
			httpResponse.getWriter().write("{\"status\":\"success\"}");
		});

		String logs = logs();
		assertThat(logs)
			.contains("[HTTP-REQUEST] method : GET, uri : /api/hello?name=Tee")
			.contains("authorization=secret-token")
			.contains("[HTTP-RESPONSE] method : GET, uri : /api/hello?name=Tee, status : 200")
			.contains("duration : ")
			.contains("body : {\"status\":\"success\"}");

		assertThat(response.getContentAsByteArray())
			.isEqualTo("{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void skipsExcludedPaths() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(properties()).doFilter(request, response, (req, res) -> {
		});

		assertThat(logs()).isEmpty();
	}

	@Test
	void truncatesLargeTextBodies() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/items");
		request.setContentType("text/plain");
		request.setContent("0123456789abcdefghij0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(new HttpLoggingProperties(true, DataSize.ofBytes(10), List.of()))
			.doFilter(request, response, (req, res) -> readBody(req));

		assertThat(logs())
			.contains("body : 0123456789...[truncated at 10 bytes]")
			.doesNotContain("0123456789abcdefghij0123456789abcdefghij");
	}

	@Test
	void describesBinaryBodiesBySize() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/files");
		request.setContentType("application/octet-stream");
		request.setContent(new byte[] { 1, 2, 3, 4 });
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(properties()).doFilter(request, response, (req, res) -> readBody(req));

		assertThat(logs()).contains("body : [binary 4 bytes]");
	}

	@Test
	void escapesNewlinesInBodies() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/logs");
		request.setContentType("text/plain");
		request.setContent("first\nsecond".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter(new HttpLoggingProperties(true, DataSize.ofKilobytes(10), List.of()))
			.doFilter(request, response, (req, res) -> readBody(req));

		assertThat(logs()).contains("body : first\\nsecond");
	}

	/** Simulates a controller consuming the request body. */
	private static void readBody(ServletRequest request) throws java.io.IOException {
		request.getInputStream().readAllBytes();
	}

	private static HttpLoggingFilter filter(HttpLoggingProperties properties) {
		return new HttpLoggingFilter(properties);
	}

	private static HttpLoggingProperties properties() {
		return new HttpLoggingProperties(true, DataSize.ofKilobytes(10), List.of("/actuator/**"));
	}

	private String logs() {
		StringBuilder builder = new StringBuilder();
		for (ILoggingEvent event : appender.list) {
			builder.append(event.getFormattedMessage()).append('\n');
		}
		return builder.toString();
	}

}
