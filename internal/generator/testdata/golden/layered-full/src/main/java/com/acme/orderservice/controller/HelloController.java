package com.acme.orderservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acme.orderservice.dto.HelloResponse;
import com.acme.orderservice.model.Greeting;
import com.acme.orderservice.common.response.JSendResponse;
import com.acme.orderservice.service.HelloService;

@RestController
@RequestMapping("/api/hello")
public class HelloController {

	private final HelloService helloService;

	public HelloController(HelloService helloService) {
		this.helloService = helloService;
	}

	@GetMapping
	public ResponseEntity<JSendResponse<HelloResponse>> hello(@RequestParam String name) {
		Greeting greeting = helloService.greet(name);
		return ResponseEntity.ok(JSendResponse.success(new HelloResponse(greeting.message())));
	}

}
