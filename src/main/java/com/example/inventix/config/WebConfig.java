package com.example.inventix.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the desktop UI call the API from another origin.
 * The allowed origins can be replaced with the {@code inventix.cors.allowed-origins} property (comma-separated).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    static final String[] ALLOWED_ORIGINS = {
            "http://tauri.localhost",   // Tauri v2 production build on Windows
            "https://tauri.localhost",  // Tauri v2 on Windows with useHttpsScheme=true
            "tauri://localhost",        // Tauri on macOS/Linux
            "http://localhost:1420",    // Tauri dev server (desktop/scripts/dev-server.js, devUrl in tauri.conf.json)
            "http://127.0.0.1:1420",
            "http://localhost:5500",    // static file server (e.g. VS Code Live Server)
            "http://127.0.0.1:5500"
    };

    static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"};

    private final String[] allowedOrigins;

    // An unset or empty property keeps the defaults above.
    public WebConfig(@Value("${inventix.cors.allowed-origins:}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins.length == 0 ? ALLOWED_ORIGINS : allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(ALLOWED_METHODS)
                .allowedHeaders("*")
                .exposedHeaders("Location")
                .maxAge(3600);
    }
}
