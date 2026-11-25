package com.emob.bff.assistant;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.emob.bff.live.LiveHub;
import com.emob.bff.motor.MotorClient;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Read-only tools of the assistant. Each one calls the motor domain API and returns compact JSON for the model.
 * The navigation tool is per request (see {@link AssistantService}).
 */
@Component
public class AssistantTools {

	private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final long DAY = 86_400_000L;

	private final MotorClient motor;
	private final LiveHub live;
	private final ObjectMapper mapper;
	private final Map<String, Function<JsonNode, Object>> handlers = new LinkedHashMap<>();
	private final List<Tool> definitions = new ArrayList<>();
	private final List<Spec> specs = new ArrayList<>();

	/** Provider-neutral description of a tool (JSON Schema properties), used by the Gemini provider. */
	public record Spec(String name, String description, Map<String, Map<String, Object>> props, List<String> required) {}

	public AssistantTools(MotorClient motor, LiveHub live, ObjectMapper mapper) {
		this.motor = motor;
		this.live = live;
		this.mapper = mapper;
		register();
	}

	public List<Tool> definitions() {
		return definitions;
	}

	public List<Spec> specs() {
		return specs;
	}

	/** Runs a tool and returns the JSON text for the tool_result block (errors are returned to the model). */
	public String run(String name, JsonNode input) {
		Function<JsonNode, Object> h = handlers.get(name);
		if (h == null) return "{\"error\":\"Unknown tool " + name + "\"}";
		try {
			ObjectNode out = mapper.createObjectNode();
			out.put("now", fmt(System.currentTimeMillis()));
			out.set("result", mapper.valueToTree(h.apply(input)));
			String s = mapper.writeValueAsString(out);
			return s.length() > 60_000 ? s.substring(0, 60_000) + "…(truncated)" : s;
		} catch (RuntimeException e) {
			return "{\"error\":" + mapper.writeValueAsString(String.valueOf(e.getMessage())) + "}";
		}
	}

	// ---------- schema helpers ----------
	private static Map<String, Object> prop(String type, String description) {
		return Map.of("type", type, "description", description);
	}

	private static Map<String, Object> enumProp(String description, String... values) {
		return Map.of("type", "string", "enum", List.of(values), "description", description);
	}

	private static final Map<String, Map<String, Object>> TIME_PROPS = Map.of(
		"sinceMinutes", Map.of("type", "integer", "minimum", 1, "maximum", 20160, "description", "Only the last N minutes (max 14 days)."),
		"from", prop("string", "Start of the interval in local time, \"YYYY-MM-DD HH:MM\" or ISO."),
		"to", prop("string", "End of the interval in local time, \"YYYY-MM-DD HH:MM\" or ISO."));

	private void tool(String name, String description, Map<String, Map<String, Object>> props, List<String> required, Function<JsonNode, Object> handler) {
		Tool.InputSchema.Properties.Builder p = Tool.InputSchema.Properties.builder();
		props.forEach((k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)));
		Tool.InputSchema.Builder schema = Tool.InputSchema.builder().properties(p.build());
		if (!required.isEmpty()) schema.required(required);
		definitions.add(Tool.builder().name(name).description(description).inputSchema(schema.build()).build());
		specs.add(new Spec(name, description, props, required));
		handlers.put(name, handler);
	}

	private static Map<String, Map<String, Object>> with(Map<String, Map<String, Object>> a, Map<String, Map<String, Object>> b) {
		Map<String, Map<String, Object>> m = new LinkedHashMap<>(a);
		m.putAll(b);
		return m;
	}

	// ---------- input helpers ----------
	private static String str(JsonNode in, String k) {
		JsonNode v = in.get(k);
		return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString();
	}

	private static Double dbl(JsonNode in, String k, Double def) {
		JsonNode v = in.get(k);
		return v == null || !v.isNumber() ? def : v.asDouble();
	}

	private static Long parseTime(String s) {
		if (s == null) return null;
		try {
			return Instant.parse(s).toEpochMilli();
		} catch (DateTimeParseException ignored) {
			// local time
		}
		try {
			String t = s.trim().replace('T', ' ');
			if (t.length() == 16) t += ":00";
			return LocalDateTime.parse(t, LOCAL).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static long[] range(JsonNode in, long defaultFromAgoMs) {
		Long from = null;
		Double since = dbl(in, "sinceMinutes", null);
		if (since != null) from = System.currentTimeMillis() - (long) (since * 60_000);
		Long f = parseTime(str(in, "from"));
		if (f != null) from = f;
		Long to = parseTime(str(in, "to"));
		return new long[] {from != null ? from : System.currentTimeMillis() - defaultFromAgoMs, to != null ? to : -1};
	}

	static String fmt(long t) {
		return LOCAL.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()));
	}

	private static Map<String, Object> q(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null && !(kv[i + 1] instanceof Long l && l < 0)) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static String pattern(String charger) {
		return charger == null ? null : charger.replaceAll("%$", "");
	}

	private ArrayNode timesToLocal(JsonNode rows, String... fields) {
		ArrayNode out = mapper.createArrayNode();
		for (JsonNode r : rows) {
			ObjectNode o = (ObjectNode) r.deepCopy();
			for (String f : fields) if (o.hasNonNull(f) && o.get(f).isNumber()) o.put(f, fmt(o.get(f).asLong()));
			out.add(o);
		}
		return out;
	}

	// ---------- tools ----------
	private void register() {
		tool("charger_health",
			"Health and maintenance-risk indicators per charger over a window: availability %, hours down, disconnections, faults "
				+ "(Faulted) and causes, MTBF/MTTR, OCPP errors per thousand messages, rejected profiles, communication gaps >15 min, "
				+ "short sessions, abnormal stops, setpoint tracking (delivered/allocated) and a 0-100 risk score (low/medium/high/critical) "
				+ "with its factors. Sorted by risk.",
			Map.of("chargerId", prop("string", "Charger ID or prefix (e.g. \"HIST-\" or \"PRUEBA-03\"). Empty = all."),
				"days", Map.of("type", "number", "minimum", 0.1, "maximum", 90, "description", "Window in days back (default 7).")),
			List.of(),
			in -> motor.get("/maintenance/health", q("chargerId", pattern(str(in, "chargerId")), "days", dbl(in, "days", 7.0))));

		tool("charger_history",
			"Time series of one charger per day or hour: incidents, faults, disconnections, availability %, OCPP errors, communication "
				+ "gaps, sessions, abnormal stops, kWh and setpoint tracking. For trends and comparing periods.",
			Map.of("chargerId", prop("string", "Exact ChargePoint ID."),
				"days", Map.of("type", "number", "minimum", 0.1, "maximum", 90, "description", "Days back (default 14)."),
				"bucket", enumProp("Aggregation (hour only for windows up to 3 days).", "day", "hour")),
			List.of("chargerId"),
			in -> motor.get("/maintenance/history", q("chargerId", str(in, "chargerId"), "days", dbl(in, "days", 14.0), "bucket", str(in, "bucket"))));

		tool("predict_failures",
			"Maintenance forecast: probability of at least one incident and of a fault within the horizon, expected incidents, trend "
				+ "(worsening/stable/improving), slope, last-day anomaly (z-score), days until 1 incident/day, setpoint-tracking "
				+ "degradation, confidence and recommendation. Without chargerId = ranking of all chargers by fault probability.",
			Map.of("chargerId", prop("string", "Exact ID or prefix; empty = all."),
				"horizonDays", Map.of("type", "number", "minimum", 1, "maximum", 60, "description", "Forecast horizon (default 7)."),
				"windowDays", Map.of("type", "number", "minimum", 3, "maximum", 90, "description", "History used to estimate (default 30).")),
			List.of(),
			in -> {
				JsonNode r = motor.get("/maintenance/forecast", q("chargerId", pattern(str(in, "chargerId")),
					"horizonDays", dbl(in, "horizonDays", 7.0), "windowDays", dbl(in, "windowDays", 30.0)));
				// With several chargers the daily series is dropped (saves tokens)
				JsonNode list = r.get("forecasts");
				if (list != null && list.size() > 1) for (JsonNode f : list) ((ObjectNode) f).remove("dailySeries");
				return r;
			});

		tool("energy_indicators",
			"Energy-management indicators and savings of the power control versus uncontrolled charging (immediate, full power): "
				+ "avoided peak, required grid connection, % of time over the limit, load factor, demand-charge savings (CLP/month), "
				+ "energy cost, CO2 avoided, savings versus fuel, and the cost of control (extra minutes, shifted energy, % sessions "
				+ "reaching their target). Demand as 15-min averages. Excludes HIST (synthetic history) by default.",
			Map.of("hours", Map.of("type", "number", "minimum", 0.25, "maximum", 1440, "description", "Hours back (default 24)."),
				"groups", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Charger ID prefixes (e.g. [\"PRUEBA\"]); empty = all except HIST.")),
			List.of(),
			in -> {
				List<String> groups = new ArrayList<>();
				if (in.has("groups") && in.get("groups").isArray()) in.get("groups").forEach(g -> groups.add(g.asString()));
				if (groups.isEmpty())
					for (JsonNode g : motor.get("/energy/groups").get("groups")) if (!"HIST".equals(g.get("group").asString())) groups.add(g.get("group").asString());
				long to = System.currentTimeMillis();
				ObjectNode r = (ObjectNode) motor.get("/energy/report",
					q("from", to - (long) (dbl(in, "hours", 24.0) * 3_600_000), "to", to, "groups", String.join(",", groups)));
				r.remove("series");
				return r;
			});

		tool("query_database",
			"Read-only custom query for calculations the other tools do not cover. Max 200 rows and 5 s. Either:\n"
				+ "- database \"postgres\" with `sql` (one SELECT/WITH statement) on the master data, or\n"
				+ "- database \"mongo\" with `collection` and `pipeline` (aggregation; allowed stages: $match, $group, $sort, $limit, "
				+ "$skip, $project, $count, $unwind, $addFields/$set, $bucket, $bucketAuto, $facet, $sortByCount) on the operational data.\n"
				+ AssistantPrompts.DATA_SCHEMA,
			Map.of("database", enumProp("Which store to query.", "postgres", "mongo"),
				"sql", prop("string", "PostgreSQL SELECT statement (database = postgres)."),
				"collection", prop("string", "Mongo collection (database = mongo)."),
				"pipeline", Map.of("type", "array", "items", Map.of("type", "object"),
					"description", "Mongo aggregation stages (database = mongo). Always $match a time range on large collections (ocpp_log, meter_samples).")),
			List.of("database"),
			in -> {
				JsonNode r = "mongo".equals(str(in, "database"))
					? motor.post("/queries/mongo", Map.of("collection", String.valueOf(str(in, "collection")),
						"pipeline", in.has("pipeline") ? in.get("pipeline") : mapper.createArrayNode()))
					: motor.post("/queries/sql", Map.of("sql", str(in, "sql") == null ? "" : str(in, "sql")));
				JsonNode rows = r.get("rows");
				boolean big = rows.toString().length() > 30_000;
				return Map.of("rows", rows.size(), "truncated", r.path("truncated").asBoolean() || big,
					"result", big ? mapper.valueToTree(rows.valueStream().limit(50).toList()) : rows);
			});

		tool("ocpp_summary",
			"Aggregated summary of the OCPP log over an interval: messages and errors per charger, count per OCPP action, and the most "
				+ "frequent errors (CALLERROR, Rejected/Faulted answers, StatusNotification with errorCode). Use it first to get oriented.",
			with(Map.of("chargerId", prop("string", "ChargePoint ID; SQL wildcard \"%\" allowed, e.g. \"PRUEBA-%\".")), TIME_PROPS),
			List.of(),
			in -> {
				long[] r = range(in, 3_600_000);
				return motor.get("/logs/summary", q("chargerId", str(in, "chargerId"), "from", r[0], "to", r[1]));
			});

		tool("search_ocpp_messages",
			"Searches concrete frames in the OCPP log (every message sent or received). direction \"in\" = charger to CSMS, \"out\" = "
				+ "CSMS to charger. kind CALL is the request; CALLRESULT/CALLERROR the answer (same action by correlation). Up to 100 rows, newest first.",
			with(Map.of(
				"chargerId", prop("string", "ChargePoint ID; SQL wildcard \"%\" allowed."),
				"action", prop("string", "Exact OCPP action, e.g. \"StatusNotification\", \"SetChargingProfile\", \"StopTransaction\"."),
				"direction", enumProp("Message direction.", "in", "out"),
				"kind", enumProp("Frame type.", "CALL", "CALLRESULT", "CALLERROR"),
				"errorsOnly", prop("boolean", "Only CALLERROR, non-accepted answers or StatusNotification with an error."),
				"text", prop("string", "Text inside the JSON payload (e.g. \"Faulted\", an idTag or a transactionId)."),
				"order", enumProp("Sort order.", "newest", "oldest"),
				"limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)), TIME_PROPS),
			List.of(),
			in -> {
				long[] r = range(in, 7 * DAY);
				JsonNode rows = motor.get("/logs/ocpp", q("chargerId", str(in, "chargerId"), "action", str(in, "action"),
					"direction", str(in, "direction"), "kind", str(in, "kind"), "errorsOnly", in.path("errorsOnly").asBoolean() ? "true" : null,
					"text", str(in, "text"), "from", r[0], "to", r[1], "limit", Math.min(dbl(in, "limit", 40.0).intValue(), 100),
					"order", "oldest".equals(str(in, "order")) ? "asc" : "desc"));
				ArrayNode out = mapper.createArrayNode();
				for (JsonNode m : rows) {
					ObjectNode o = mapper.createObjectNode();
					o.put("time", fmt(m.get("t").asLong()));
					o.set("chargerId", m.get("charger_id"));
					o.set("direction", m.get("dir"));
					o.set("kind", m.get("kind"));
					o.set("action", m.get("action"));
					if (m.hasNonNull("error_code")) o.set("error", m.get("error_code"));
					String p = m.path("payload").asString();
					o.put("payload", p.length() > 500 ? p.substring(0, 500) + "…" : p);
					out.add(o);
				}
				return Map.of("rows", out.size(), "messages", out);
			});

		tool("search_engine_events",
			"Events of the power engine: connections/disconnections, session start/end, setpoints sent (level \"power\"), warnings and errors. Newest first.",
			with(Map.of(
				"chargerId", prop("string", "ChargePoint ID; SQL wildcard \"%\" allowed."),
				"level", enumProp("Event level.", "info", "warn", "error", "power"),
				"text", prop("string", "Text contained in the event message."),
				"limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)), TIME_PROPS),
			List.of(),
			in -> {
				long[] r = range(in, 7 * DAY);
				JsonNode rows = motor.get("/logs/events", q("chargerId", str(in, "chargerId"), "level", str(in, "level"),
					"text", str(in, "text"), "from", r[0], "to", r[1], "limit", Math.min(dbl(in, "limit", 40.0).intValue(), 100)));
				return timesToLocal(rows, "t");
			});

		tool("current_state",
			"Live state: yard configuration (limit, auxiliary loads, strategy), available and used power, and every charger with its "
				+ "connection, last contact and connectors (status, session, vehicle, measured/allocated power, SoC).",
			Map.of("chargerId", prop("string", "Optional: only this ChargePoint ID (prefix allowed).")),
			List.of(),
			in -> {
				String snap = live.lastSnapshot();
				JsonNode s = snap != null ? mapper.readTree(snap).get("data") : motor.get("/state");
				String p = pattern(str(in, "chargerId"));
				ArrayNode chargers = mapper.createArrayNode();
				for (JsonNode c : s.get("chargers")) {
					if (p != null && !c.get("id").asString().startsWith(p)) continue;
					ObjectNode o = mapper.createObjectNode();
					o.set("id", c.get("id"));
					o.set("online", c.get("connected"));
					if (c.hasNonNull("lastSeen")) o.put("lastContact", fmt(c.get("lastSeen").asLong()));
					o.set("maxPowerKw", c.get("maxPowerKw"));
					o.set("priority", c.get("priority"));
					ArrayNode conns = o.putArray("connectors");
					for (JsonNode k : c.get("connectors")) {
						ObjectNode x = conns.addObject();
						x.set("id", k.get("connectorId"));
						x.set("status", k.get("status"));
						if (!"NoError".equals(k.path("errorCode").asString())) x.set("error", k.get("errorCode"));
						if (k.hasNonNull("transactionId")) x.set("session", k.get("transactionId"));
						if (k.hasNonNull("vehicle")) x.set("vehicle", k.get("vehicle").get("name"));
						if (k.hasNonNull("identMethod")) x.set("identification", k.get("identMethod"));
						x.put("measuredKw", Math.round(k.path("powerW").asDouble() / 10) / 100.0);
						x.put("allocatedKw", Math.round(k.path("allocatedW").asDouble() / 10) / 100.0);
						if (k.hasNonNull("soc")) x.set("soc", k.get("soc"));
						if (k.hasNonNull("startedAt")) x.put("started", fmt(k.get("startedAt").asLong()));
					}
					chargers.add(o);
				}
				JsonNode cfg = s.get("config");
				Map<String, Object> yard = new LinkedHashMap<>();
				yard.put("limitKw", cfg.get("siteLimitKw"));
				yard.put("auxiliaryKw", Math.round(s.path("buildingLoadW").asDouble() / 10) / 100.0);
				yard.put("availableKw", Math.round(s.path("availableW").asDouble() / 10) / 100.0);
				yard.put("strategy", cfg.get("strategy"));
				yard.put("safetyMarginKw", cfg.get("safetyMarginKw"));
				yard.put("measuredKwTotal", Math.round(s.path("totalPowerW").asDouble() / 10) / 100.0);
				yard.put("allocatedKwTotal", Math.round(s.path("totalAllocatedW").asDouble() / 10) / 100.0);
				yard.put("activeSessions", s.get("activeSessions"));
				return Map.of("yard", yard, "chargers", chargers);
			});

		tool("sessions",
			"Charging sessions (ongoing and finished) with vehicle, identification method, energy, start/end SoC, maximum power and stop reason.",
			with(Map.of(
				"chargerId", prop("string", "ChargePoint ID prefix."),
				"vehicle", prop("string", "Text in the vehicle name."),
				"limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)), TIME_PROPS),
			List.of(),
			in -> {
				long[] r = range(in, DAY);
				JsonNode rows = motor.get("/sessions", q("from", r[0], "to", r[1] > 0 ? r[1] : System.currentTimeMillis() + 60_000));
				String p = pattern(str(in, "chargerId"));
				String veh = str(in, "vehicle");
				List<Map<String, Object>> out = new ArrayList<>();
				for (JsonNode s : rows) {
					if (p != null && !s.get("charger_id").asString().startsWith(p)) continue;
					if (veh != null && !s.path("vehicle_name").asString("").toLowerCase().contains(veh.toLowerCase())) continue;
					boolean liveNow = s.path("live").asBoolean();
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", s.get("transaction_id").asLong());
					m.put("charger", s.get("charger_id").asString() + "/" + s.get("connector_id").asInt());
					m.put("vehicle", s.hasNonNull("vehicle_name") ? s.get("vehicle_name").asString() : "unidentified");
					m.put("identification", s.path("ident_method").asString(null));
					m.put("idTag", s.path("id_tag").asString(null));
					m.put("start", fmt(s.get("started_at").asLong()));
					m.put("end", liveNow ? "ongoing" : s.hasNonNull("stopped_at") ? fmt(s.get("stopped_at").asLong()) : null);
					m.put("kWh", Math.round(s.path("energy_wh").asDouble() / 10) / 100.0);
					m.put("socStart", s.hasNonNull("soc_start") ? s.get("soc_start").asDouble() : null);
					m.put("socEnd", s.hasNonNull("soc_end") ? s.get("soc_end").asDouble() : null);
					if (s.hasNonNull("max_power_w")) m.put("maxKw", Math.round(s.get("max_power_w").asDouble() / 100) / 10.0);
					if (s.hasNonNull("stop_reason")) m.put("stopReason", s.get("stop_reason").asString());
					out.add(m);
				}
				int limit = Math.min(dbl(in, "limit", 40.0).intValue(), 100);
				List<Map<String, Object>> last = out.subList(Math.max(0, out.size() - limit), out.size());
				return Map.of("rows", last.size(), "sessions", last);
			});
	}
}
