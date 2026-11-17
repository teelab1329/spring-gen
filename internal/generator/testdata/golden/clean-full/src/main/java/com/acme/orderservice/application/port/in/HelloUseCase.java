package com.acme.orderservice.application.port.in;

import com.acme.orderservice.domain.model.Greeting;

public interface HelloUseCase {

	Greeting greet(String name);

	Greeting remoteGreeting(String name);

}
