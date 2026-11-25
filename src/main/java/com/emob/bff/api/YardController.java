package com.emob.bff.api;

import com.emob.bff.assistant.AssistantService;
import com.emob.bff.live.LiveHub;
import com.emob.bff.motor.MotorClient;
import com.emob.bff.motor.MotorProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Yard state, engine configuration and environment information for the front. */
@RestController
@RequestMapping("/api/v1")
public class YardController {

	private final MotorClient motor;
	private final LiveHub live;
	private final MotorProperties props;
	private final AssistantService assistant;
	private final ObjectMapper mapper;

	public YardController(MotorClient motor, LiveHub live, MotorProperties props, AssistantService assistant, ObjectMapper mapper) {
		this.motor = motor;
		this.live = live;
		this.props = props;
		this.assistant = assistant;
		this.mapper = mapper;
	}

	/** Where the motor is (OCPP URL, emulator) and which optional features are available. */
	@GetMapping("/info")
	public Map<String, Object> info(HttpServletRequest req) {
		String base;
		if (props.publicUrl() != null && !props.publicUrl().isBlank()) {
			base = props.publicUrl().replaceAll("/+$", "");
		} else {
			base = req.getScheme() + "://" + req.getServerName() + ":" + props.publicPort();
		}
		String ws = base.replaceFirst("^http", "ws");
		return Map.of(
			"motorConnected", live.isMotorConnected(),
			"gatewayUrl", base,
			"ocppUrl", ws + "/ocpp/{chargePointId}",
			"emulatorUrl", base + "/emulator/",
			"assistantConfigured", assistant.isConfigured());
	}

	/** Live state: the latest snapshot pushed by the motor, or a fresh one when there is none. */
	@GetMapping("/state")
	public JsonNode state() {
		String snap = live.lastSnapshot();
		if (snap != null) return mapper.readTree(snap).get("data");
		return motor.get("/state");
	}

	@GetMapping("/config")
	public JsonNode config() {
		return motor.get("/config");
	}

	/** Partial update; the motor validates and keeps only known keys. */
	@PutMapping("/config")
	public JsonNode updateConfig(@RequestBody Map<String, Object> patch) {
		return motor.put("/config", patch);
	}
}
