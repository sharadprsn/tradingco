package com.kite.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for HTTP log forwarding.
 * 
 * These properties control the custom Logback {@link LogbackHttpAppender}
 * which sends log events as JSON to a remote HTTP endpoint (e.g. a log
 * aggregator or a webhook).
 * 
 * <p>Example {@code application.properties}:
 * <pre>{@code
 * logging.http.url=https://logs.example.com/ingest
 * logging.http.level=WARN
 * }</pre>
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@Component
@ConfigurationProperties(prefix = "logging.http")
public class LoggingConfig {

    private String url;
    private String level;

    public LoggingConfig() {
    }

    /**
     * The target HTTP(S) URL where log events will be POSTed as JSON.
     * When empty or not set, the HTTP appender is effectively disabled.
     */
    public String getUrl() {
        return url;
    }

    public void setUrl(final String url) {
        this.url = url;
    }

    /**
     * Minimum log level for forwarding via HTTP (e.g. {@code WARN}, {@code ERROR}).
     * Defaults to {@code WARN} when not set.
     */
    public String getLevel() {
        return level;
    }

    public void setLevel(final String level) {
        this.level = level;
    }
}
