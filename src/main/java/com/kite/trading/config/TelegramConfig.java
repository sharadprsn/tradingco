package com.kite.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "telegram")
public class TelegramConfig {

    private String botToken;
    private String chatId;
    private String baseUrl;

    public TelegramConfig() {
    }

    public TelegramConfig(final String botToken, final String chatId, final String baseUrl) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.baseUrl = baseUrl;
    }

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(final String botToken) {
        this.botToken = botToken;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(final String chatId) {
        this.chatId = chatId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
