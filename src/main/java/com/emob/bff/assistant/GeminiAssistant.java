package com.emob.bff.assistant;

import com.emob.bff.assistant.AssistantService.Reply;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini provider of the assistant (free tier friendly): the same read-only tools as the Claude provider,
 * through the Gemini REST API with function calling (manual loop). Used when GEMINI_API_KEY is set.
 */
@Component
public class GeminiAssistant {

	private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

	private final AssistantTools tools;
	private final ObjectMapper mapper;
	private final String apiKey;
	private final String model;
	private final List<String> fallbacks;
	private final int maxIterations;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.version(HttpClient.Version.HTTP_1_1).build();

	public GeminiAssistant(AssistantTools tools, ObjectMapper mapper, @Value("${assistant.gemini-api-key:}") String apiKey,
		@Value("${assistant.gemini-model:gemini-2.5-flash}") String model,
		@Value("${assistant.gemini-fallback-models:gemini-2.5-flash,gemini-flash-lite-latest}") String fallbacks,
		@Value("${assistant.max-iterations:14}") int maxIterations) {
		this.tools = tools;
		this.mapper = mapper;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.model = model;
		this.fallbacks = java.util.Arrays.stream(fallbacks.split(",")).map(String::trim).filter(s -> !s.isEmpty() && !s.equals(model)).toList();
		this.maxIterations = maxIterations;
	}

	public boolean isConfigured() {
		return !apiKey.isEmpty();
	}

	/** OpenAPI-subset schema Gemini accepts: upper-case types; an object with no properties is passed as a JSON string. */
	private ObjectNode schema(Map<String, Object> p) {
		ObjectNode s = mapper.createObjectNode();
		String type = String.valueOf(p.getOrDefault("type", "string"));
		if ("object".equals(type)) {
			s.put("type", "STRING");
			s.put("description", "A JSON object, written as JSON text." + (p.containsKey("description") ? " " + p.get("description") : ""));
			return s;
		}
		s.put("type", type.toUpperCase());
		if (p.get("description") != null) s.put("description", String.valueOf(p.get("description")));
		if (p.get("enum") instanceof List<?> values) {
			ArrayNode e = s.putArray("enum");
			values.forEach(v -> e.add(String.valueOf(v)));
		}
		if (p.get("minimum") instanceof Number n) s.put("minimum", n.doubleValue());
		if (p.get("maximum") instanceof Number n) s.put("maximum", n.doubleValue());
		if (p.get("items") instanceof Map<?, ?> items) {
			@SuppressWarnings("unchecked")
			Map<String, Object> it = (Map<String, Object>) items;
			s.set("items", schema(it));
		}
		return s;
	}

	private ObjectNode declaration(String name, String description, Map<String, Map<String, Object>> props, List<String> required) {
		ObjectNode d = mapper.createObjectNode();
		d.put("name", name);
		d.put("description", description);
		ObjectNode params = d.putObject("parameters");
		params.put("type", "OBJECT");
		ObjectNode ps = params.putObject("properties");
		props.forEach((k, v) -> ps.set(k, schema(v)));
		if (props.isEmpty()) ps.putObject("_").put("type", "STRING").put("description", "Unused.");
		if (!required.isEmpty()) {
			ArrayNode r = params.putArray("required");
			required.forEach(r::add);
		}
		return d;
	}

	/** Arguments passed as JSON text for object-typed values are turned back into JSON (e.g. a Mongo pipeline). */
	private JsonNode normalizeArgs(JsonNode args) {
		if (!(args instanceof ObjectNode obj)) return mapper.createObjectNode();
		ObjectNode out = obj.deepCopy();
		for (String k : new ArrayList<>(out.propertyNames())) {
			JsonNode v = out.get(k);
			if (v.isArray()) {
				ArrayNode fixed = mapper.createArrayNode();
				for (JsonNode e : v) fixed.add(e.isString() && e.asString().trim().startsWith("{") ? parseOr(e) : e);
				out.set(k, fixed);
			} else if (v.isString() && v.asString().trim().startsWith("{")) out.set(k, parseOr(v));
		}
		return out;
	}

	private JsonNode parseOr(JsonNode text) {
		try {
			return mapper.readTree(text.asString());
		} catch (RuntimeException e) {
			return text;
		}
	}

	/** Busy model (503/500) or rate limit (429): retry with backoff, then the fallback models. */
	private JsonNode post(ObjectNode body) {
		List<String> models = new ArrayList<>();
		models.add(model);
		models.addAll(fallbacks);
		ResponseStatusException last = null;
		for (String m : models) {
			for (int attempt = 0; attempt < 1; attempt++) { // one try per model: a busy model is skipped quickly
				try {
					return postOnce(m, body);
				} catch (Busy e) {
					last = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
						"Gemini is busy right now (" + e.getMessage() + "); try again in a minute.");
					try {
						Thread.sleep(1500L * (attempt + 1));
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw last;
					}
				}
			}
		}
		throw last;
	}

	private static final class Busy extends RuntimeException {
		Busy(String msg) {
			super(msg, null, false, false);
		}
	}

	private JsonNode postOnce(String model, ObjectNode body) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + model + ":generateContent"))
			.timeout(Duration.ofSeconds(35))
			.header("Content-Type", "application/json")
			.header("x-goog-api-key", apiKey)
			.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
			.build();
		HttpResponse<String> res;
		try {
			res = http.send(req, HttpResponse.BodyHandlers.ofString());
		} catch (java.net.http.HttpTimeoutException e) {
			throw new Busy(model + " timeout");
		} catch (java.io.IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No connection to the Gemini API.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Interrupted while calling the Gemini API.");
		}
		JsonNode json = parseBody(res.body());
		int code = res.statusCode();
		if (code == 200) return json;
		String msg = json.path("error").path("message").asString("");
		// busy, rate-limited or retired model (404): move on to the next model
		if (code == 404 || code == 429 || code == 500 || code == 503 || code == 504) throw new Busy(model + " " + code);
		if (code == 400 && msg.toLowerCase().contains("api key") || code == 401 || code == 403)
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid Gemini credentials. Check GEMINI_API_KEY.");
		throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini API error (" + code + "): " + msg);
	}

	private JsonNode parseBody(String body) {
		try {
			return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
		} catch (RuntimeException e) {
			return mapper.createObjectNode();
		}
	}

	public Reply ask(List<AssistantService.Turn> turns, ObjectNode navigationDeclaration) {
		ObjectNode body = mapper.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", AssistantPrompts.SYSTEM);
		ArrayNode decls = body.putArray("tools").addObject().putArray("functionDeclarations");
		for (AssistantTools.Spec s : tools.specs()) decls.add(declaration(s.name(), s.description(), s.props(), s.required()));
		decls.add(navigationDeclaration);
		ArrayNode contents = body.putArray("contents");
		for (AssistantService.Turn t : turns)
			contents.addObject().put("role", t.user() ? "user" : "model").putArray("parts").addObject().put("text", t.text());

		List<Map<String, Object>> steps = new ArrayList<>();
		Map<String, Long> usage = new LinkedHashMap<>(Map.of("input", 0L, "output", 0L, "cacheRead", 0L));
		Map<String, Object> navigate = null;
		JsonNode content = null;
		String finish = "";
		for (int i = 0; i < maxIterations; i++) {
			JsonNode res = post(body);
			JsonNode um = res.path("usageMetadata");
			usage.merge("input", um.path("promptTokenCount").asLong(0), Long::sum);
			usage.merge("output", um.path("candidatesTokenCount").asLong(0), Long::sum);
			usage.merge("cacheRead", um.path("cachedContentTokenCount").asLong(0), Long::sum);
			JsonNode cand = res.path("candidates").path(0);
			finish = cand.path("finishReason").asString("");
			content = cand.path("content");
			if (content.isMissingNode() || !content.path("parts").isArray()) {
				String blocked = res.path("promptFeedback").path("blockReason").asString("");
				return new Reply(blocked.isEmpty() ? "_(The model returned no answer. Try rephrasing the question.)_"
					: "The model could not answer this question because of its safety policies. Please rephrase it.", steps, usage, finish, navigate);
			}
			contents.add(content.deepCopy()); // keeps any thought signatures the model needs back
			ArrayNode responses = mapper.createArrayNode();
			for (JsonNode part : content.path("parts")) {
				JsonNode fc = part.get("functionCall");
				if (fc == null) continue;
				String name = fc.path("name").asString("");
				JsonNode args = normalizeArgs(fc.path("args"));
				String output;
				if ("navigate_dashboard".equals(name)) {
					navigate = mapper.convertValue(args, Map.class);
					output = "{\"result\":\"Navigation prepared: the dashboard will open that view when showing your answer.\"}";
				} else {
					steps.add(Map.of("tool", name, "input", mapper.convertValue(args, Map.class)));
					output = tools.run(name, args);
				}
				ObjectNode fr = responses.addObject().putObject("functionResponse");
				fr.put("name", name);
				JsonNode parsed = parseBody(output);
				fr.set("response", parsed.isObject() ? parsed : mapper.createObjectNode().put("result", output));
			}
			if (responses.isEmpty()) break;
			contents.addObject().put("role", "user").set("parts", responses);
		}

		StringBuilder sb = new StringBuilder();
		if (content != null)
			for (JsonNode part : content.path("parts"))
				if (part.has("text") && !part.path("thought").asBoolean(false))
					sb.append(sb.isEmpty() ? "" : "\n").append(part.path("text").asString(""));
		String reply = sb.toString().trim();
		if ("MAX_TOKENS".equals(finish)) reply += "\n\n_(Answer cut for length.)_";
		if (reply.isEmpty() && !steps.isEmpty()) reply = "_(The assistant used all its queries without reaching an answer. Try a narrower question.)_";
		return new Reply(reply, steps, usage, finish, navigate);
	}
}
