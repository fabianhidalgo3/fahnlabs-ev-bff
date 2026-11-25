package com.emob.bff.api;

import com.emob.bff.api.dto.Requests.ChargerRequest;
import com.emob.bff.api.dto.Requests.ChargingPlanRequest;
import com.emob.bff.api.dto.Requests.CommandRequest;
import com.emob.bff.api.dto.Requests.VehicleRequest;
import com.emob.bff.motor.MotorClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

/** Vehicles, chargers (with OCPP commands), charging plans and sessions. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class FleetController {

	private static final String CHARGER_ID = "^[\\w.-]{1,48}$";
	private final MotorClient motor;

	public FleetController(MotorClient motor) {
		this.motor = motor;
	}

	private static String seg(String s) {
		return UriUtils.encodePathSegment(s, java.nio.charset.StandardCharsets.UTF_8);
	}

	// ---------- vehicles ----------
	@GetMapping("/vehicles")
	public JsonNode vehicles() {
		return motor.get("/vehicles");
	}

	@GetMapping("/vehicles/{id}")
	public JsonNode vehicle(@PathVariable long id) {
		return motor.get("/vehicles/" + id);
	}

	@PostMapping("/vehicles")
	@ResponseStatus(HttpStatus.CREATED)
	public JsonNode createVehicle(@Valid @RequestBody VehicleRequest body) {
		return motor.post("/vehicles", body);
	}

	@PutMapping("/vehicles/{id}")
	public JsonNode updateVehicle(@PathVariable long id, @Valid @RequestBody VehicleRequest body) {
		return motor.put("/vehicles/" + id, body);
	}

	@DeleteMapping("/vehicles/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteVehicle(@PathVariable long id) {
		motor.delete("/vehicles/" + id);
	}

	// ---------- chargers ----------
	@GetMapping("/chargers")
	public JsonNode chargers() {
		return motor.get("/chargers");
	}

	@PostMapping("/chargers")
	@ResponseStatus(HttpStatus.CREATED)
	public JsonNode registerCharger(@Valid @RequestBody ChargerRequest body) {
		if (body.id() == null) throw new IllegalArgumentException("id is required");
		return motor.post("/chargers", body);
	}

	@PutMapping("/chargers/{id}")
	public JsonNode updateCharger(@PathVariable @Pattern(regexp = CHARGER_ID) String id, @Valid @RequestBody ChargerRequest body) {
		return motor.put("/chargers/" + seg(id), body);
	}

	@DeleteMapping("/chargers/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeCharger(@PathVariable @Pattern(regexp = CHARGER_ID) String id) {
		motor.delete("/chargers/" + seg(id));
	}

	/** OCPP command: remote-start, remote-stop or reset. Answers the charger's response status. */
	@PostMapping("/chargers/{id}/{command:remote-start|remote-stop|reset}")
	public JsonNode command(@PathVariable @Pattern(regexp = CHARGER_ID) String id, @PathVariable String command,
		@Valid @RequestBody(required = false) CommandRequest body) {
		return motor.command("/chargers/" + seg(id) + "/" + command, body != null ? body : Map.of());
	}

	/**
	 * Any other OCPP 1.6 command (ChangeAvailability, UnlockConnector, GetConfiguration, ChangeConfiguration,
	 * TriggerMessage, ClearChargingProfile, GetCompositeSchedule…). The body is the OCPP payload; the answer is the
	 * charger's CALLRESULT payload.
	 */
	@PostMapping("/chargers/{id}/ocpp/{action:[A-Z][A-Za-z]{2,40}}")
	public JsonNode ocppCommand(@PathVariable @Pattern(regexp = CHARGER_ID) String id, @PathVariable String action,
		@RequestBody(required = false) Map<String, Object> payload) {
		return motor.command("/chargers/" + seg(id) + "/ocpp/" + action, payload != null ? payload : Map.of());
	}

	// ---------- charging plans ----------
	@GetMapping("/charging-plans")
	public JsonNode plans(@RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
		return motor.get("/charging-plans", query("from", from, "to", to));
	}

	@PostMapping("/charging-plans")
	@ResponseStatus(HttpStatus.CREATED)
	public JsonNode createPlan(@Valid @RequestBody ChargingPlanRequest body) {
		return motor.post("/charging-plans", body);
	}

	@DeleteMapping("/charging-plans/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancelPlan(@PathVariable long id) {
		motor.delete("/charging-plans/" + id);
	}

	// ---------- sessions ----------
	@GetMapping("/sessions")
	public JsonNode sessions(@RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
		return motor.get("/sessions", query("from", from, "to", to));
	}

	@GetMapping("/sessions/{transactionId}/samples")
	public JsonNode samples(@PathVariable long transactionId) {
		return motor.get("/sessions/" + transactionId + "/samples");
	}

	static Map<String, Object> query(Object... kv) {
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
		return m;
	}
}
