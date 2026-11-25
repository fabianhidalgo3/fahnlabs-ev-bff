package com.emob.bff.motor;

import org.springframework.http.HttpStatusCode;

/** Error returned by the motor, or the motor being unreachable (503). */
public class MotorException extends RuntimeException {

	private final HttpStatusCode status;

	public MotorException(HttpStatusCode status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatusCode getStatus() {
		return status;
	}
}
