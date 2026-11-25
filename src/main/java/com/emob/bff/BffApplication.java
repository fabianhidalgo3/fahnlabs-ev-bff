package com.emob.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend for Frontend of the EV yard power control dashboard.
 *
 * <p>Exposes the REST API ({@code /api/v1}) and the live WebSocket ({@code /ws/live}) consumed by the
 * Angular front, runs the AI assistant and serves the compiled front. It never touches the database nor
 * speaks OCPP: everything goes through the domain API of the motor service.
 *
 * @author Fabián Hidalgo
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BffApplication {

	public static void main(String[] args) {
		// API messages (validation, problem details) are always in English; the front translates the UI
		java.util.Locale.setDefault(java.util.Locale.ENGLISH);
		SpringApplication.run(BffApplication.class, args);
	}
}
