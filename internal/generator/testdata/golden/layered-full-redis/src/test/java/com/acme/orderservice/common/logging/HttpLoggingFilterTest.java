package com.acme.orderservice.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
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
	void logsRequestBeforeBusinessAndResponseAfter() throws Exception {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> everyEvent = new ListAppender<>();
		everyEvent.start();
		root.addAppender(everyEvent);

		try {
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
			request.setContentType("application/json");
			request.setContent("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
			Logger business = (Logger) LoggerFactory.getLogger("business.test");

			filter(properties()).doFilter(request, new MockHttpServletResponse(),
				(req, res) -> business.info("[BUSINESS-ERROR] order rejected"));

			List<String> messages = everyEvent.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
			int requestLine = indexOf(messages, "[HTTP-REQUEST]");
			int businessLine = indexOf(messages, "[BUSINESS-ERROR]");
			int responseLine = indexOf(messages, "[HTTP-RESPONSE]");

			assertThat(requestLine).isGreaterThanOrEqualTo(0);
			assertThat(businessLine).isGreaterThan(requestLine);
			assertThat(responseLine).isGreaterThan(businessLine);
		}
		finally {
			root.detachAppender(everyEvent);
		}
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
	void streamsLargeBodiesWhileTruncatingTheLog() throws Exception {
		byte[] body = "0123456789abcdefghij".repeat(5).getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/items");
		request.setContentType("text/plain");
		request.setContent(body);

		AtomicInteger received = new AtomicInteger();
		filter(new HttpLoggingProperties(true, DataSize.ofBytes(10), List.of()))
			.doFilter(request, new MockHttpServletResponse(),
				(req, res) -> received.set(req.getInputStream().readAllBytes().length));

		assertThat(received).hasValue(body.length);
		assertThat(logs())
			.contains("body : 0123456789...[truncated at 10 bytes]")
			.doesNotContain("0123456789abcdefghij0123456789abcdefghij");
	}

	@Test
	void logsFormParametersWithoutConsumingTheStream() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/login");
		request.setContentType("application/x-www-form-urlencoded");
		request.setParameter("username", "tee");
		request.setParameter("password", "secret");

		String[] seen = new String[1];
		filter(properties()).doFilter(request, new MockHttpServletResponse(),
			(req, res) -> seen[0] = req.getParameter("username"));

		assertThat(seen[0]).isEqualTo("tee");
		assertThat(logs()).contains("body : {password=secret, username=tee}");
	}

	@Test
	void describesBinaryBodiesBySizeWithoutReadingThem() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/files");
		request.setContentType("application/octet-stream");
		request.setContent(new byte[] { 1, 2, 3, 4 });

		AtomicInteger received = new AtomicInteger();
		filter(properties()).doFilter(request, new MockHttpServletResponse(),
			(req, res) -> received.set(req.getInputStream().readAllBytes().length));

		assertThat(received).hasValue(4);
		assertThat(logs()).contains("body : [binary 4 bytes]");
	}

	@Test
	void escapesNewlinesInBodies() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/logs");
		request.setContentType("text/plain");
		request.setContent("first\nsecond".getBytes(StandardCharsets.UTF_8));

		filter(properties()).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
		});

		assertThat(logs()).contains("body : first\\nsecond");
	}

	private static int indexOf(List<String> messages, String needle) {
		for (int i = 0; i < messages.size(); i++) {
			if (messages.get(i).contains(needle)) {
				return i;
			}
		}
		return -1;
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
