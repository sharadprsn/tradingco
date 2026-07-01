package com.kite.trading.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for static resource handling.
 * 
 * Registers a handler for {@code /favicon.ico} to return {@code 204 No Content}
 * instead of a {@code 404} error, preventing browser noise in logs.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * {@inheritDoc}
     */
    @Override
    public void addViewControllers(final ViewControllerRegistry registry) {
        registry.addViewController("/favicon.ico")
                .setStatusCode(HttpStatus.NO_CONTENT);
    }
}
