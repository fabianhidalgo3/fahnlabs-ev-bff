package com.emob.bff.api;

import com.emob.bff.motor.MotorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every API error is answered as RFC 9457 problem details (application/problem+json). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(MotorException.class)
	ProblemDetail motor(MotorException e) {
		ProblemDetail p = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
		p.setTitle(e.getStatus().value() == 503 ? "Motor unavailable" : "Rejected by the motor");
		return p;
	}

	/** Bean Validation errors: the detail lists every invalid field. */
	@Override
	protected org.springframework.http.ResponseEntity<Object> handleMethodArgumentNotValid(
		org.springframework.web.bind.MethodArgumentNotValidException ex, org.springframework.http.HttpHeaders headers,
		org.springframework.http.HttpStatusCode status, org.springframework.web.context.request.WebRequest request) {
		java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
		ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, fields.entrySet().stream()
			.map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
		p.setTitle("Invalid request");
		p.setProperty("fields", fields);
		return org.springframework.http.ResponseEntity.badRequest().body(p);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}
}
