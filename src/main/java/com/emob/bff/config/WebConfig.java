package com.emob.bff.config;

import com.emob.bff.live.LiveHub;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Serves the compiled Angular front (files) and registers the live WebSocket. SPA routes: {@link SpaController}. */
@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {

	private final Path frontDir;
	private final LiveHub liveHub;

	public WebConfig(@Value("${front.dir}") String frontDir, LiveHub liveHub) {
		this.frontDir = Path.of(frontDir).toAbsolutePath().normalize();
		this.liveHub = liveHub;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// Same origin in production; the Angular dev server proxies /ws during development
		registry.addHandler(liveHub, "/ws/live").setAllowedOriginPatterns("*");
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = frontDir.toUri().toString();
		// Hashed bundles never change: cache for a year
		registry.addResourceHandler("/*.js", "/*.css", "/media/**")
			.addResourceLocations(location)
			.setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
		// index.html, favicon…: always revalidate
		registry.addResourceHandler("/**").addResourceLocations(location).setCacheControl(CacheControl.noCache());
	}
}
