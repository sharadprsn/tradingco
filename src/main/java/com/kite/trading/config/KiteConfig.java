package com.kite.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for Zerodha Kite API settings.
 * 
 * This configuration holds all the API credentials and settings
 * required to interact with the Zerodha Kite API.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@Configuration
@ConfigurationProperties(prefix = "kite")
public class KiteConfig {

    private String apiKey;
    private String apiSecret;
    private String baseUrl;
    private String loginUrl;
    private String redirectUrl;

    public KiteConfig() {
    }

    public KiteConfig(final String apiKey, final String apiSecret,
                      final String baseUrl, final String loginUrl,
                      final String redirectUrl) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.baseUrl = baseUrl;
        this.loginUrl = loginUrl;
        this.redirectUrl = redirectUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(final String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(final String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public void setLoginUrl(final String loginUrl) {
        this.loginUrl = loginUrl;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public void setRedirectUrl(final String redirectUrl) {
        this.redirectUrl = redirectUrl;
    }
}
