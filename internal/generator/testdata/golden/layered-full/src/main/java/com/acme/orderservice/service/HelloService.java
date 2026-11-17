package com.acme.orderservice.service;

import org.springframework.stereotype.Service;

import com.acme.orderservice.common.exception.ValidationException;
import com.acme.orderservice.model.Greeting;

@Service
public class HelloService {

	public Greeting greet(String name) {
		if (name == null || name.isBlank()) {
			throw new ValidationException("Name must not be blank.");
		}
		return new Greeting("Hello, " + name.trim() + "!");
	}

}
