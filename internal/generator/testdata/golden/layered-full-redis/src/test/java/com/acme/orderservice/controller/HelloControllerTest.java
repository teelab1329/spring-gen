package com.acme.orderservice.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.acme.orderservice.client.GreetingClient;
import com.acme.orderservice.model.Greeting;
import com.acme.orderservice.service.HelloService;

@WebMvcTest(HelloController.class)
@Import(HelloService.class)
class HelloControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private GreetingClient greetingGateway;

	@Test
	void returnsSuccessForValidName() throws Exception {
		mockMvc.perform(get("/api/hello").param("name", "Tee"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("success"))
			.andExpect(jsonPath("$.data.message").value("Hello, Tee!"));
	}

	@Test
	void returnsFailWithReadableMessageForBlankName() throws Exception {
		mockMvc.perform(get("/api/hello").param("name", " "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value("fail"))
			.andExpect(jsonPath("$.message").value("Name must not be blank."))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void returnsFailForMissingName() throws Exception {
		mockMvc.perform(get("/api/hello"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value("fail"))
			.andExpect(jsonPath("$.message").value("Missing required parameter 'name'."))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void callsTheDownstreamClientForRemoteGreeting() throws Exception {
		given(greetingGateway.greet("Tee")).willReturn(new Greeting("Hello, Tee (remote)!"));

		mockMvc.perform(get("/api/hello/remote").param("name", "Tee"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("success"))
			.andExpect(jsonPath("$.data.message").value("Hello, Tee (remote)!"));
	}

}
