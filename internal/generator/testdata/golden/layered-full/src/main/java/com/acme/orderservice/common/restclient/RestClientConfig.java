package com.acme.orderservice.common.restclient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RestClientProperties.class)
class RestClientConfig {

}
