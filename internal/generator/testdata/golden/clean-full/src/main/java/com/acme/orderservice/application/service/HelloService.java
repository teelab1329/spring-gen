package com.acme.orderservice.application.service;

import org.springframework.stereotype.Service;

import com.acme.orderservice.domain.exception.ValidationException;
import com.acme.orderservice.domain.model.Greeting;
import com.acme.orderservice.application.port.in.HelloUseCase;

@Service
public class HelloService implements HelloUseCase {

	public Greeting greet(String name) {
		if (name == null || name.isBlank()) {
			throw new ValidationException("name", "must not be blank");
		}
		return new Greeting("Hello, " + name.trim() + "!");
	}

}
