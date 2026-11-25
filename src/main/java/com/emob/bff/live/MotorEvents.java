package com.emob.bff.live;

import com.emob.bff.motor.MotorProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Subscription to the motor's live events ({@code /api/v1/events}) with automatic reconnection
 * (exponential backoff up to 10 s). Keeps the last snapshot and forwards every message to the {@link LiveHub}.
 */
@Component
public class MotorEvents {

	private static final Logger log = LoggerFactory.getLogger(MotorEvents.class);

	private final MotorProperties props;
	private final LiveHub hub;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "motor-events");
		t.setDaemon(true);
		return t;
	});
	private final AtomicReference<WebSocket> socket = new AtomicReference<>();
	private volatile boolean connected;
	private volatile boolean stopping;
	private int retry;

	public MotorEvents(MotorProperties props, LiveHub hub) {
		this.props = props;
		this.hub = hub;
	}

	public boolean isConnected() {
		return connected;
	}

	@PostConstruct
	void start() {
		scheduler.execute(this::connect);
	}

	@PreDestroy
	void stop() {
		stopping = true;
		WebSocket ws = socket.get();
		if (ws != null) ws.abort();
		scheduler.shutdownNow();
	}

	private void connect() {
		if (stopping) return;
		URI uri = URI.create(props.url().replaceFirst("^http", "ws") + "/api/v1/events");
		WebSocket.Builder b = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));
		if (props.token() != null && !props.token().isBlank()) b.header("X-Motor-Token", props.token());
		b.buildAsync(uri, new Listener()).whenComplete((ws, err) -> {
			if (err != null) scheduleReconnect();
			else socket.set(ws);
		});
	}

	private void scheduleReconnect() {
		if (stopping) return;
		if (connected) {
			connected = false;
			log.warn("Motor events disconnected; retrying ({})", props.url());
			hub.motorStatus(false);
		}
		long wait = Math.min(10_000, 500L << Math.min(retry++, 5));
		scheduler.schedule(this::connect, wait, TimeUnit.MILLISECONDS);
	}

	private final class Listener implements WebSocket.Listener {

		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket ws) {
			retry = 0;
			connected = true;
			log.info("Motor events connected ({})", props.url());
			hub.motorStatus(true);
			ws.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				hub.publishMotorMessage(buffer.toString());
				buffer.setLength(0);
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			scheduleReconnect();
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			scheduleReconnect();
		}
	}
}
