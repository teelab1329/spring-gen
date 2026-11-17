package com.acme.orderservice.application.service;

import org.springframework.stereotype.Service;

import com.acme.orderservice.domain.exception.ValidationException;
import com.acme.orderservice.domain.model.Greeting;
import com.acme.orderservice.application.port.out.GreetingGateway;
import com.acme.orderservice.application.port.in.HelloUseCase;

@Service
public class HelloService implements HelloUseCase {

	private final GreetingGateway greetingGateway;

	public HelloService(GreetingGateway greetingGateway) {
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
