package com.kite.trading.service;

import com.kite.trading.dto.OptionChainData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class FallbackOptionChainClient implements OptionChainClient {

    private static final Logger logger = LoggerFactory.getLogger(FallbackOptionChainClient.class);

    private final NseOptionChainClient nseClient;
    private final YahooFinanceOptionChainClient yahooClient;

    public FallbackOptionChainClient(final NseOptionChainClient nseClient,
                                     final YahooFinanceOptionChainClient yahooClient) {
        this.nseClient = nseClient;
        this.yahooClient = yahooClient;
    }

    @Override
    public OptionChainData fetchOptionChain() {
        OptionChainData nseData = nseClient.fetchOptionChain();
        if (isValid(nseData)) {
            return nseData;
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            logger.warn("NSE option chain unavailable, retrying (attempt {}/2)...", attempt);
            try {
                Thread.sleep(5000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            nseData = nseClient.fetchOptionChain();
            if (isValid(nseData)) {
                return nseData;
            }
        }

        logger.warn("NSE option chain failed after 3 attempts, falling back to Yahoo Finance");
        final OptionChainData yahooData = yahooClient.fetchOptionChain();
        if (isValid(yahooData)) {
            return yahooData;
        }

        logger.error("Both NSE and Yahoo Finance option chain sources failed");
        return null;
    }

    private static boolean isValid(final OptionChainData data) {
        return data != null && data.records() != null
                && data.records().data() != null && !data.records().data().isEmpty();
    }
}
