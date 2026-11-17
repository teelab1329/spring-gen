package dev.th1329.test.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.th1329.test.dto.HelloResponse;
import dev.th1329.test.model.Greeting;
import dev.th1329.test.common.response.JSendResponse;
import dev.th1329.test.service.HelloService;

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
