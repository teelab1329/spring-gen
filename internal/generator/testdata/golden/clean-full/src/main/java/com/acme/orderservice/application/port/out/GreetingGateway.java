package com.acme.orderservice.application.port.out;

import com.acme.orderservice.domain.model.Greeting;

/** Outbound port for greeting another service. */
public interface GreetingGateway {

	Greeting greet(String name);

}
