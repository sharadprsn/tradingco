package com.kite.trading.service;

import com.kite.trading.config.TelegramConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class TelegramServiceImpl implements TelegramService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramServiceImpl.class);

    private final WebClient webClient;
    private final TelegramConfig telegramConfig;

    public TelegramServiceImpl(final WebClient webClient, final TelegramConfig telegramConfig) {
        this.webClient = webClient;
        this.telegramConfig = telegramConfig;
    }

    @Override
    public void sendMessage(final String chatId, final String text) {
        logger.info("Sending Telegram message to chat: {}", chatId);

        try {
            final String url = telegramConfig.getBaseUrl() + "/bot" + telegramConfig.getBotToken() + "/sendMessage";

            webClient.post()
                    .uri(url)
                    .bodyValue(Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML"))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.debug("Telegram message sent successfully");
        } catch (final Exception e) {
            logger.error("Failed to send Telegram message to chat: {}", chatId, e);
        }
    }

    @Override
    public void sendMessage(final String text) {
        sendMessage(telegramConfig.getChatId(), text);
    }
}
