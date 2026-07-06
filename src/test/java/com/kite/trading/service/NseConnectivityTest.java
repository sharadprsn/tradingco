package com.kite.trading.service;

import com.kite.trading.config.NseConfig;
import com.kite.trading.dto.OptionChainData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class NseConnectivityTest {

    @Autowired
    private OptionChainClient optionChainClient;

    @Autowired
    private NseConfig nseConfig;

    @Test
    void verifyNseConfig() {
        assertNotNull(nseConfig.getHomeUrl());
        assertNotNull(nseConfig.getOptionChainUrl());
        assertNotNull(nseConfig.getUserAgent());
        assertTrue(nseConfig.getOptionChainUrl().contains("NIFTY"));
    }

    @Test
    void fetchOptionChainFromNse() {
        final OptionChainData data = optionChainClient.fetchOptionChain();
        assertNotNull(data, "Option chain data should not be null");
        assertNotNull(data.records(), "Records should not be null");
        assertNotNull(data.records().data(), "Options data list should not be null");
        assertFalse(data.records().data().isEmpty(), "Should have at least one option contract");
        assertNotNull(data.records().underlyingValue(), "Underlying value should be present");
        assertTrue(data.records().underlyingValue().compareTo(java.math.BigDecimal.ZERO) > 0,
                "Underlying value should be positive");
        assertNotNull(data.records().expiryDates(), "Expiry dates should not be null");
        assertFalse(data.records().expiryDates().isEmpty(), "Should have at least one expiry date");
        assertNotNull(data.records().strikePrices(), "Strike prices should not be null");
        assertFalse(data.records().strikePrices().isEmpty(), "Should have at least one strike price");
    }

    @Test
    void optionDataHasValidContracts() {
        final OptionChainData data = optionChainClient.fetchOptionChain();
        assertNotNull(data);
        assertNotNull(data.records());

        final var sampleOption = data.records().data().stream()
                .filter(d -> d.ce() != null || d.pe() != null)
                .findFirst()
                .orElse(null);
        assertNotNull(sampleOption, "At least one option should have CE or PE contract");

        if (sampleOption.ce() != null) {
            assertNotNull(sampleOption.ce().strikePrice());
            assertNotNull(sampleOption.ce().openInterest());
            assertNotNull(sampleOption.ce().impliedVolatility());
            assertNotNull(sampleOption.ce().lastPrice());
        }
        if (sampleOption.pe() != null) {
            assertNotNull(sampleOption.pe().strikePrice());
            assertNotNull(sampleOption.pe().openInterest());
            assertNotNull(sampleOption.pe().impliedVolatility());
            assertNotNull(sampleOption.pe().lastPrice());
        }
    }
}
