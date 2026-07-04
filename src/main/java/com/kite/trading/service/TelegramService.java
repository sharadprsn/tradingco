package com.kite.trading.service;

public interface TelegramService {

    void sendMessage(String chatId, String text);

    void sendMessage(String text);
}
