package com.emob.bff.motor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client of the motor domain API ({@code /api/v1}). Bodies are passed through as JSON trees so the BFF
 * can adapt them without duplicating the domain model.
 */
@Component
public class MotorClient {

	private final RestClient rest;
	private final RestClient commands;
	private final ObjectMapper mapper;
	private final MotorProperties props;

	public MotorClient(MotorProperties props, ObjectMapper mapper) {
		this.props = props;
		this.mapper = mapper;
		this.rest = build(RestClient.builder(), props.timeout());
		this.commands = build(RestClient.builder(), props.commandTimeout());
	}

	private RestClient build(RestClient.Builder builder, Duration timeout) {
		// HTTP/1.1: the default h2c upgrade header would be taken as a WebSocket upgrade by the motor's Node server
		HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(timeout);
		builder.baseUrl(props.url() + "/api/v1").requestFactory(factory);
		if (props.token() != null && !props.token().isBlank()) builder.defaultHeader("X-Motor-Token", props.token());
		return builder
			.defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
				throw new MotorException(res.getStatusCode(), problemDetail(res.getBody().readAllBytes(), res.getStatusCode()));
			})
			.build();
	}

	private String problemDetail(byte[] body, HttpStatusCode status) {
		try {
			JsonNode n = mapper.readTree(body);
			if (n.hasNonNull("detail")) return n.get("detail").asString();
			if (n.hasNonNull("error")) return n.get("error").asString();
		} catch (RuntimeException ignored) {
			// not JSON
		}
		return "Motor error " + status.value();
	}

	public JsonNode get(String path) {
		return get(path, Map.of());
	}

	public JsonNode get(String path, Map<String, ?> query) {
		return call(() -> rest.get().uri(u -> withQuery(u.path(path), query)).retrieve().body(JsonNode.class));
	}

	public JsonNode post(String path, Object body) {
		return call(() -> rest.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
	}

	/** OCPP commands wait for the charger's answer, hence a longer timeout. */
	public JsonNode command(String path, Object body) {
		return call(() -> commands.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
	}

	public JsonNode put(String path, Object body) {
		return call(() -> rest.put().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
	}

	public void delete(String path) {
		call(() -> rest.delete().uri(path).retrieve().toBodilessEntity());
	}

	private static java.net.URI withQuery(UriBuilder u, Map<String, ?> query) {
		query.forEach((k, v) -> {
			if (v != null && !String.valueOf(v).isBlank()) u.queryParam(k, v);
		});
		return u.build();
	}

	private <T> T call(java.util.function.Supplier<T> fn) {
		try {
			return fn.get();
		} catch (ResourceAccessException e) {
			throw new MotorException(HttpStatus.SERVICE_UNAVAILABLE,
				"The motor is not reachable at " + props.url() + ". Check that it is running.");
		}
	}
}
