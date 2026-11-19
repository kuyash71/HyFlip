package com.hyflip.server.config;

import com.hyflip.server.security.AuthTokenFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Allows the Tauri webview and the Vite dev server to call the API. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "tauri://localhost",        // Tauri webview on Linux and macOS
                        "http://tauri.localhost",   // Tauri webview on Windows
                        "http://localhost:1420")    // Vite dev server
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders(AuthTokenFilter.HEADER, "Content-Type");
    }
}
