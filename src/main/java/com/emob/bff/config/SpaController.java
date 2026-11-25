package com.emob.bff.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side routes of the Angular front (/, /energy, /motor…) are answered with index.html.
 * Paths with a dot (files), /api, /ws, /actuator and the OpenAPI docs (/v3, /swagger-ui) are not matched here.
 */
@Controller
public class SpaController {

	private static final String ROUTE = "^(?!api$|ws$|actuator$|v3$|swagger-ui$)[^.]*$";

	@GetMapping({"/", "/{route:" + ROUTE + "}", "/{route:" + ROUTE + "}/{rest:[^.]*}"})
	public String index() {
		return "forward:/index.html";
	}
}
