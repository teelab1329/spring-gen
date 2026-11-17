package com.acme.orderservice.service;

import org.springframework.stereotype.Service;

import com.acme.orderservice.common.exception.ValidationException;
import com.acme.orderservice.model.Greeting;
import com.acme.orderservice.client.GreetingClient;

@Service
public class HelloService {

	private final GreetingClient greetingGateway;

	public HelloService(GreetingClient greetingGateway) {
		this.greetingGateway = greetingGateway;
	}

	public Greeting greet(String name) {
		if (name == null || name.isBlank()) {
			throw new ValidationException("Name must not be blank.");
		}
		return new Greeting("Hello, " + name.trim() + "!");
	}

	public Greeting remoteGreeting(String name) {
		if (name == null || name.isBlank()) {
			throw new ValidationException("Name must not be blank.");
		}
		return greetingGateway.greet(name.trim());
	}

}
