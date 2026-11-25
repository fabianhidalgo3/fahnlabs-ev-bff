package com.emob.bff.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.emob.bff.api.dto.Requests.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Operations assistant: Claude answers questions with read-only tools that call the motor domain API
 * (manual tool-use loop with the official Anthropic Java SDK).
 */
@Service
public class AssistantService {

	/** Result returned to the front. */
	public record Reply(String reply, List<Map<String, Object>> steps, Map<String, Long> usage, String stop, Map<String, Object> navigate) {}

	private static final List<String> SECTIONS = List.of("ops", "patio", "energy", "motor", "calendar", "vehicles", "chargers", "data", "emulator");

	/** One conversation turn, provider-neutral. */
	public record Turn(boolean user, String text) {}

	private final AssistantTools tools;
	private final GeminiAssistant gemini;
	private final ObjectMapper mapper;
	private final String apiKey;
	private final String model;
	private final int maxIterations;
	private volatile AnthropicClient client;

	public AssistantService(AssistantTools tools, GeminiAssistant gemini, ObjectMapper mapper, @Value("${assistant.api-key:}") String apiKey,
		@Value("${assistant.model}") String model, @Value("${assistant.max-iterations:14}") int maxIterations) {
		this.tools = tools;
		this.gemini = gemini;
		this.mapper = mapper;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.model = model;
		this.maxIterations = maxIterations;
	}

	/** Claude when ANTHROPIC_API_KEY is set, otherwise Gemini when GEMINI_API_KEY is set. */
	public boolean isConfigured() {
		return !apiKey.isEmpty() || gemini.isConfigured();
	}

	private tools.jackson.databind.node.ObjectNode navigationDeclaration() {
		var d = mapper.createObjectNode();
		d.put("name", "navigate_dashboard");
		d.put("description", "Takes the user to a section of the dashboard (optionally filtering by charger and OCPP action in the Motor "
			+ "section). Use it when the user asks to go/see/open something, or at the end of an answer when that view helps.");
		var params = d.putObject("parameters");
		params.put("type", "OBJECT");
		var props = params.putObject("properties");
		var section = props.putObject("section");
		section.put("type", "STRING");
		section.put("description", "ops=Operations, patio=Live yard, energy=Energy savings, motor=commands and OCPP log, "
			+ "calendar=Calendar, vehicles, chargers, data=Sessions and data, emulator=Web emulator");
		var values = section.putArray("enum");
		SECTIONS.forEach(values::add);
		props.putObject("chargerId").put("type", "STRING").put("description", "ChargePoint ID to highlight or filter (optional).");
		props.putObject("action").put("type", "STRING").put("description", "OCPP action to filter the log in Motor, e.g. MeterValues (optional).");
		params.putArray("required").add("section");
		return d;
	}

	private AnthropicClient client() {
		if (client == null) {
			synchronized (this) {
				if (client == null) client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
			}
		}
		return client;
	}

	private static Tool navigationTool() {
		Tool.InputSchema.Properties props = Tool.InputSchema.Properties.builder()
			.putAdditionalProperty("section", JsonValue.from(Map.of("type", "string", "enum", SECTIONS, "description",
				"ops=Operations, patio=Live yard, energy=Energy savings, motor=commands and OCPP log, calendar=Calendar, "
					+ "vehicles, chargers, data=Sessions and data, emulator=Web emulator")))
			.putAdditionalProperty("chargerId", JsonValue.from(Map.of("type", "string", "description", "ChargePoint ID to highlight or filter (optional).")))
			.putAdditionalProperty("action", JsonValue.from(Map.of("type", "string", "description", "OCPP action to filter the log in Motor, e.g. MeterValues (optional).")))
			.build();
		return Tool.builder()
			.name("navigate_dashboard")
			.description("Takes the user to a section of the dashboard (optionally filtering by charger and OCPP action in the Motor section). "
				+ "Use it when the user asks to go/see/open something, or at the end of an answer when that view helps.")
			.inputSchema(Tool.InputSchema.builder().properties(props).required(List.of("section")).build())
			.build();
	}

	public Reply ask(List<ChatMessage> history, String language) {
		if (!isConfigured())
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				"The assistant has no credentials. Set ANTHROPIC_API_KEY (Claude) or GEMINI_API_KEY (Gemini) and restart the BFF.");
		List<ChatMessage> msgs = new ArrayList<>(history.size() > 20 ? history.subList(history.size() - 20, history.size()) : history);
		while (!msgs.isEmpty() && !"user".equals(msgs.getFirst().role())) msgs.removeFirst();
		if (msgs.isEmpty() || !"user".equals(msgs.getLast().role())) throw new IllegalArgumentException("The last message must be the user's question");

		if (apiKey.isEmpty()) {
			String language1 = "es".equals(language) ? "Spanish" : "English";
			List<Turn> turns = new ArrayList<>();
			for (ChatMessage m : msgs) {
				String text = m.content().length() > 8000 ? m.content().substring(0, 8000) : m.content();
				boolean user = "user".equals(m.role());
				turns.add(new Turn(user, turns.isEmpty() ? "[Dashboard language: " + language1 + "]\n" + text : text));
			}
			return gemini.ask(turns, navigationDeclaration());
		}

		MessageCreateParams.Builder params = MessageCreateParams.builder()
			.model(model)
			.maxTokens(16000L)
			.systemOfTextBlockParams(List.of(TextBlockParam.builder().text(AssistantPrompts.SYSTEM)
				.cacheControl(CacheControlEphemeral.builder().build()).build()))
			.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build());
		for (Tool t : tools.definitions()) params.addTool(t);
		params.addTool(navigationTool());

		String lang = "es".equals(language) ? "Spanish" : "English";
		boolean first = true;
		for (ChatMessage m : msgs) {
			String text = m.content().length() > 8000 ? m.content().substring(0, 8000) : m.content();
			if ("user".equals(m.role())) {
				params.addUserMessage(first ? "[Dashboard language: " + lang + "]\n" + text : text);
				first = false;
			} else params.addAssistantMessage(text);
		}

		List<Map<String, Object>> steps = new ArrayList<>();
		Map<String, Long> usage = new LinkedHashMap<>(Map.of("input", 0L, "output", 0L, "cacheRead", 0L));
		Map<String, Object> navigate = null;
		Message last = null;
		try {
			for (int i = 0; i < maxIterations; i++) {
				last = client().messages().create(params.build());
				usage.merge("input", last.usage().inputTokens(), Long::sum);
				usage.merge("output", last.usage().outputTokens(), Long::sum);
				usage.merge("cacheRead", last.usage().cacheReadInputTokens().orElse(0L), Long::sum);
				params.addMessage(last);
				if (!last.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) break;

				List<ContentBlockParam> results = new ArrayList<>();
				for (ContentBlock block : last.content()) {
					if (block.toolUse().isEmpty()) continue;
					ToolUseBlock tu = block.toolUse().get();
					JsonNode input = mapper.readTree(toJson(tu._input()));
					String output;
					if ("navigate_dashboard".equals(tu.name())) {
						navigate = mapper.convertValue(input, Map.class);
						output = "Navigation prepared: the dashboard will open that view when showing your answer.";
					} else {
						steps.add(Map.of("tool", tu.name(), "input", mapper.convertValue(input, Map.class)));
						output = tools.run(tu.name(), input);
					}
					results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
				}
				params.addUserMessageOfContentBlockParams(results);
			}
		} catch (UnauthorizedException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid Anthropic credentials. Check ANTHROPIC_API_KEY in bff/.env.");
		} catch (PermissionDeniedException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The Anthropic key has no access to this model.");
		} catch (RateLimitException e) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "API rate limit reached; try again in a few seconds.");
		} catch (AnthropicIoException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No connection to the Anthropic API.");
		} catch (AnthropicServiceException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Anthropic API error (" + e.statusCode() + "): " + e.getMessage());
		}

		if (last == null) throw new IllegalStateException("No answer from the model");
		String stop = last.stopReason().map(Object::toString).orElse("");
		if ("refusal".equals(stop))
			return new Reply("The model could not answer this question because of its safety policies. Please rephrase it.", steps, usage, stop, null);
		StringBuilder sb = new StringBuilder();
		for (ContentBlock b : last.content()) b.text().ifPresent(t -> sb.append(sb.isEmpty() ? "" : "\n").append(t.text()));
		String reply = sb.toString().trim();
		if ("max_tokens".equals(stop)) reply += "\n\n_(Answer cut for length.)_";
		if (reply.isEmpty() && !steps.isEmpty()) reply = "_(The assistant used all its queries without reaching an answer. Try a narrower question.)_";
		return new Reply(reply, steps, usage, stop, navigate);
	}

	private String toJson(JsonValue v) {
		// The SDK serialises its JsonValue with Jackson 2; the BFF reads it back with Jackson 3
		try {
			return com.anthropic.core.ObjectMappers.jsonMapper().writeValueAsString(v);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			return "{}";
		}
	}
}
