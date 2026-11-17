package com.acme.orderservice.infrastructure.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.acme.orderservice.infrastructure.web.dto.HelloResponse;
import com.acme.orderservice.domain.model.Greeting;
import com.acme.orderservice.infrastructure.web.response.JSendResponse;
import com.acme.orderservice.application.port.in.HelloUseCase;

@RestController
@RequestMapping("/api/hello")
public class HelloController {

	private final HelloUseCase helloService;

	public HelloController(HelloUseCase helloService) {
		this.helloService = helloService;
	}

	@GetMapping
	public ResponseEntity<JSendResponse<HelloResponse>> hello(@RequestParam String name) {
		Greeting greeting = helloService.greet(name);
		return ResponseEntity.ok(JSendResponse.success(new HelloResponse(greeting.message())));
	}

	/** Calls this same service over the pooled REST client configured for "greeting". */
	@GetMapping("/remote")
	public ResponseEntity<JSendResponse<HelloResponse>> remoteHello(@RequestParam String name) {
		Greeting greeting = helloService.remoteGreeting(name);
		return ResponseEntity.ok(JSendResponse.success(new HelloResponse(greeting.message())));
	}

}
