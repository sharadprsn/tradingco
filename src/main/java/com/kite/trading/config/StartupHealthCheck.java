package com.kite.trading.config;

import com.kite.trading.dto.OptionChainData;
import com.kite.trading.service.OptionChainClient;
import com.kite.trading.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class StartupHealthCheck {

    private static final Logger logger = LoggerFactory.getLogger(StartupHealthCheck.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OptionChainClient optionChainClient;
    private final TelegramService telegramService;

    public StartupHealthCheck(final OptionChainClient optionChainClient,
                              final TelegramService telegramService) {
        this.optionChainClient = optionChainClient;
        this.telegramService = telegramService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("========================================");
        logger.info("  STARTUP HEALTH CHECKS");
        logger.info("  Time: {}", LocalDateTime.now(IST).format(DateTimeFormatter.ISO_LOCAL_TIME));
        logger.info("========================================");

        checkNseConnectivity();

        logger.info("========================================");
        logger.info("  STARTUP HEALTH CHECKS COMPLETE");
        logger.info("========================================");
    }

    private void checkNseConnectivity() {
        logger.info("[NSE] Checking connectivity to NSE website...");
        try {
            final OptionChainData data = optionChainClient.fetchOptionChain();
            if (data != null && data.records() != null) {
                final var records = data.records();
                final boolean hasData = records.data() != null && !records.data().isEmpty();
                final boolean hasOi = hasData && records.data().stream()
                        .anyMatch(o -> (o.pe() != null && o.pe().openInterest() != null)
                                || (o.ce() != null && o.ce().openInterest() != null));
                logger.info("[NSE] Connection OK - underlying: {}, data records: {}, OI data available: {}",
                        records.underlyingValue(),
                        hasData ? records.data().size() : 0,
                        hasOi);
                if (records.underlyingValue() != null) {
                    final var firstOption = records.data().getFirst();
                    final BigDecimal peOi = firstOption.pe() != null ? firstOption.pe().openInterest() : null;
                    final BigDecimal ceOi = firstOption.ce() != null ? firstOption.ce().openInterest() : null;
                    logger.info("[NSE] Sample OI at strike {} - PE: {}, CE: {}",
                            firstOption.strikePrice(), formatOi(peOi), formatOi(ceOi));
                }
            } else {
                logger.warn("[NSE] Connection established but no option chain data returned (markets may be closed)");
            }
        } catch (final Exception e) {
            logger.error("[NSE] Connection FAILED - {}", e.getMessage());
        }
    }

    private static String formatOi(final BigDecimal value) {
        if (value == null) {
            return "N/A";
        }
        final long longVal = value.longValue();
        if (longVal >= 1_00_00_000) {
            return String.format("%.1fCr", longVal / 1_00_00_000.0);
        }
        if (longVal >= 1_00_000) {
            return String.format("%.1fL", longVal / 1_00_000.0);
        }
        return String.valueOf(longVal);
    }
}
