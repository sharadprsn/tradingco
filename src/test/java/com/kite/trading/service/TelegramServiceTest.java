package com.kite.trading.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TelegramServiceTest {

    @Autowired
    private TelegramService telegramService;

    @Test
    void sendHelloToTelegram() {
        telegramService.sendMessage("hello");
    }
}
