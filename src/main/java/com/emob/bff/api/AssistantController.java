package com.emob.bff.api;

import com.emob.bff.api.dto.Requests.AssistantRequest;
import com.emob.bff.assistant.AssistantService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operations assistant (Claude with read-only tools). */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

	private final AssistantService assistant;

	public AssistantController(AssistantService assistant) {
		this.assistant = assistant;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of("configured", assistant.isConfigured());
	}

	@PostMapping("/messages")
	public AssistantService.Reply ask(@Valid @RequestBody AssistantRequest req) {
		return assistant.ask(req.messages(), req.language());
	}
}
