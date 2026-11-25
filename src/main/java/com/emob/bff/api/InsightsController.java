package com.emob.bff.api;

import static com.emob.bff.api.FleetController.query;

import com.emob.bff.motor.MotorClient;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** OCPP logs, energy indicators, predictive maintenance and CSV exports. */
@RestController
@RequestMapping("/api/v1")
public class InsightsController {

	private static final Map<String, String> EXPORTS = Map.of(
		"sessions.csv", "sessions", "meter-samples.csv", "meter-samples", "site-samples.csv", "site-samples",
		"vehicles.csv", "vehicles", "chargers.csv", "chargers");

	private final MotorClient motor;

	public InsightsController(MotorClient motor) {
		this.motor = motor;
	}

	/** kind: ocpp | events | summary | commands | command-stats | actions. Filters are passed through. */
	@GetMapping("/logs/{kind:ocpp|events|summary|commands|command-stats|actions}")
	public JsonNode logs(@PathVariable String kind, @RequestParam Map<String, String> filters) {
		return motor.get("/logs/" + kind, filters);
	}

	@GetMapping("/energy/report")
	public JsonNode energyReport(@RequestParam(required = false) Long from, @RequestParam(required = false) Long to,
		@RequestParam(required = false) String groups) {
		return motor.get("/energy/report", query("from", from, "to", to, "groups", groups));
	}

	@GetMapping("/energy/groups")
	public JsonNode energyGroups() {
		return motor.get("/energy/groups");
	}

	/** kind: health | history | forecast. */
	@GetMapping("/maintenance/{kind:health|history|forecast}")
	public JsonNode maintenance(@PathVariable String kind, @RequestParam Map<String, String> params) {
		return motor.get("/maintenance/" + kind, params);
	}

	/** CSV export (UTF-8 with BOM so spreadsheets open it correctly). */
	@GetMapping("/exports/{name}")
	public void export(@PathVariable String name, @RequestParam Map<String, String> params, HttpServletResponse res) throws IOException {
		String source = EXPORTS.get(name);
		if (source == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown export " + name);
		JsonNode rows = motor.get("/exports/" + source, params);
		res.setContentType("text/csv; charset=UTF-8");
		res.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
		res.setCharacterEncoding(StandardCharsets.UTF_8.name());
		PrintWriter w = res.getWriter();
		w.write('﻿');
		List<String> cols = new ArrayList<>();
		if (rows.isArray() && !rows.isEmpty()) rows.get(0).propertyNames().forEach(cols::add);
		w.println(String.join(",", cols));
		for (JsonNode r : rows) {
			List<String> cells = new ArrayList<>(cols.size());
			for (String c : cols) cells.add(csv(r.get(c)));
			w.println(String.join(",", cells));
		}
		w.flush();
	}

	private static String csv(JsonNode v) {
		if (v == null || v.isNull()) return "";
		String s = v.isValueNode() ? v.asString() : v.toString();
		return s.matches("(?s).*[\",\n;].*") ? '"' + s.replace("\"", "\"\"") + '"' : s;
	}
}
