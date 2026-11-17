package com.acme.orderservice.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.acme.orderservice.dto.HelloResponse;
import com.acme.orderservice.model.Greeting;
import com.acme.orderservice.common.response.JSendResponse;
import com.acme.orderservice.common.restclient.RestClientFactory;

/**
 * Sample downstream client. Uses the pooled client configured under
 * {@code app.rest-clients.clients.greeting}.
 */
@Component
public class GreetingClient {

	private static final ParameterizedTypeReference<JSendResponse<HelloResponse>> RESPONSE_TYPE =
		new ParameterizedTypeReference<>() {
		};

	private final RestClient client;

	@Autowired
	public GreetingClient(RestClientFactory clients) {
		this.client = clients.get("greeting");
	}

	GreetingClient(RestClient client) {
		this.client = client;
	}

	public Greeting greet(String name) {
		JSendResponse<HelloResponse> response = client.get()
			.uri((uri) -> uri.path("/api/hello").queryParam("name", name).build())
			.retrieve()
			.body(RESPONSE_TYPE);
		if (response == null || response.data() == null) {
			throw new IllegalStateException("greeting service returned no payload");
		}
		return new Greeting(response.data().message());
	}

}
