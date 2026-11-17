package com.acme.orderservice.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.acme.orderservice.service.HelloService;

@WebMvcTest(HelloController.class)
@Import(HelloService.class)
class HelloControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void returnsSuccessForValidName() throws Exception {
		mockMvc.perform(get("/api/hello").param("name", "Tee"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("success"))
			.andExpect(jsonPath("$.data.message").value("Hello, Tee!"));
	}

	@Test
	void returnsFailForBlankName() throws Exception {
		mockMvc.perform(get("/api/hello").param("name", " "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value("fail"))
			.andExpect(jsonPath("$.data.name").value("must not be blank"));
	}

	@Test
	void returnsFailForMissingName() throws Exception {
		mockMvc.perform(get("/api/hello"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value("fail"));
	}

}
