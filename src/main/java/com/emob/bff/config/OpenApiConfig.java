package com.emob.bff.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description of the BFF (spec at /v3/api-docs, Swagger UI at /swagger-ui.html). */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI bffOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("EV yard power control — BFF API")
				.version("v1")
				.description("""
					REST API of the dashboard (Backend for Frontend). Validates input and delegates to the motor \
					(domain service). Errors are RFC 9457 problem details (application/problem+json). Times are epoch \
					milliseconds. Live state: WebSocket /ws/live (not described here).""")
				.contact(new Contact().name("Fabián Hidalgo"))
				.license(new License().name("All rights reserved")))
			.servers(List.of(new Server().url("/").description("This BFF")));
	}
}
