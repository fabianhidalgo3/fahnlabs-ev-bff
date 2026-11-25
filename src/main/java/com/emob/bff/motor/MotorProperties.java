package com.emob.bff.motor;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings of the motor (domain service).
 *
 * @param url            base URL of the motor, e.g. {@code http://localhost:9100}
 * @param token          optional shared secret sent as {@code X-Motor-Token}
 * @param publicPort     gateway port as seen by browsers (emulator link, OCPP URL)
 * @param publicUrl      optional public base URL of the gateway behind a reverse proxy, e.g.
 *                       {@code https://ev.fahnlabs.cl}; when set it replaces host + {@code publicPort}
 * @param timeout        timeout of regular calls
 * @param commandTimeout timeout of OCPP commands (remote start/stop, reset)
 */
@ConfigurationProperties("motor")
public record MotorProperties(String url, String token, int publicPort, String publicUrl, Duration timeout,
		Duration commandTimeout) {}
