package com.kite.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for Kite Trading API.
 * 
 * This Spring Boot application provides RESTful endpoints to interact with
 * Zerodha's Kite API for trading operations including authentication,
 * position management, and intraday trading.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@SpringBootApplication
public class KiteTradingApplication {

    /**
     * Entry point for the Kite Trading application.
     *
     * @param args Command line arguments passed to the application
     */
    public static void main(final String[] args) {
        SpringApplication.run(KiteTradingApplication.class, args);
    }
}
