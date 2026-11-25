package com.emob.bff.live;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Browser side of the live state ({@code /ws/live}). Messages:
 * <ul>
 *   <li>{@code {"type":"snapshot","data":{...}}} — motor state, forwarded as is (~2 per second)</li>
 *   <li>{@code {"type":"motor","data":{"connected":true|false}}} — BFF ↔ motor link status</li>
 * </ul>
 */
@Component
public class LiveHub extends TextWebSocketHandler {

	private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
	private volatile String lastSnapshot;
	private volatile boolean motorConnected;

	/** Last snapshot message received from the motor (raw JSON), or null. */
	public String lastSnapshot() {
		return lastSnapshot;
	}

	public boolean isMotorConnected() {
		return motorConnected;
	}

	void publishMotorMessage(String json) {
		// The motor sends {"type":"snapshot","data":{...}}; key order is not guaranteed by JSON, so check both ends
		if (json.startsWith("{\"type\":\"snapshot\"") || json.endsWith("\"type\":\"snapshot\"}")) lastSnapshot = json;
		broadcast(json);
	}

	void motorStatus(boolean connected) {
		motorConnected = connected;
		broadcast(statusMessage());
	}

	private String statusMessage() {
		return "{\"type\":\"motor\",\"data\":{\"connected\":" + motorConnected + "}}";
	}

	private void broadcast(String json) {
		TextMessage msg = new TextMessage(json);
		for (WebSocketSession s : sessions) send(s, msg);
	}

	private void send(WebSocketSession s, TextMessage msg) {
		try {
			if (s.isOpen()) s.sendMessage(msg);
		} catch (IOException | IllegalStateException e) {
			sessions.remove(s);
		}
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		// Slow clients must not block the broadcast: 5 s / 4 MB send buffer per session, oldest dropped
		WebSocketSession s = new ConcurrentWebSocketSessionDecorator(session, 5_000, 4 * 1024 * 1024,
			ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP);
		sessions.add(s);
		send(s, new TextMessage(statusMessage()));
		String snap = lastSnapshot;
		if (snap != null) send(s, new TextMessage(snap));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		sessions.removeIf(s -> s.getId().equals(session.getId()));
	}
}
