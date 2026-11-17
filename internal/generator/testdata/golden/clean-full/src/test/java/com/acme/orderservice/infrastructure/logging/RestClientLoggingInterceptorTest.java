package com.acme.orderservice.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

class RestClientLoggingInterceptorTest {

	private Logger logger;

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachAppender() {
		logger = (Logger) LoggerFactory.getLogger(RestClientLoggingInterceptor.class);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
	}

	@Test
	void logsOutgoingRequestAndResponse() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("http://localhost:8080")
			.requestInterceptor(new RestClientLoggingInterceptor(properties()));
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		server.expect(requestTo("http://localhost:8080/api/users"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "secret-token"))
			.andRespond(withStatus(HttpStatus.CREATED)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"id\":\"u-1\"}"));

		String body = builder.build()
			.post()
			.uri("/api/users")
			.header("Authorization", "secret-token")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"name\":\"Tee\"}")
			.retrieve()
			.body(String.class);

		assertThat(body).isEqualTo("{\"id\":\"u-1\"}");

		String logs = logs();
		assertThat(logs)
			.contains("[RESTCLIENT-REQUEST] method : POST, uri : http://localhost:8080/api/users")
			.contains("authorization=***")
			.contains("body : {\"name\":\"Tee\"}")
			.contains("[RESTCLIENT-RESPONSE] method : POST, uri : http://localhost:8080/api/users, status : 201")
			.contains("body : {\"id\":\"u-1\"}");

		server.verify();
	}

	private static HttpLoggingProperties properties() {
		return new HttpLoggingProperties(true, DataSize.ofKilobytes(10),
			List.of("authorization"), List.of());
	}

	private String logs() {
		StringBuilder builder = new StringBuilder();
		for (ILoggingEvent event : appender.list) {
			builder.append(event.getFormattedMessage()).append('\n');
		}
		return builder.toString();
	}

}
