package dev.th1329.test.service;

import org.springframework.stereotype.Service;

import dev.th1329.test.common.exception.ValidationException;
import dev.th1329.test.model.Greeting;

@Service
public class HelloService {

	public Greeting greet(String name) {
		if (name == null || name.isBlank()) {
			throw new ValidationException("Name must not be blank.");
		}
		return new Greeting("Hello, " + name.trim() + "!");
	}

}
