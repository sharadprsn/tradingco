package com.kite.trading.service;

import com.kite.trading.config.NseConfig;
import com.kite.trading.dto.OptionChainData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class NseOptionChainClient implements OptionChainClient {

    private static final Logger logger = LoggerFactory.getLogger(NseOptionChainClient.class);

    private final WebClient webClient;
    private final NseConfig nseConfig;

    private volatile String sessionCookie;

    public NseOptionChainClient(final WebClient webClient, final NseConfig nseConfig) {
        this.webClient = webClient;
        this.nseConfig = nseConfig;
    }

    public OptionChainData fetchOptionChain() {
        ensureSession();
        return fetchData();
    }

    private void ensureSession() {
        if (sessionCookie == null) {
            logger.info("Establishing NSE session");
            final var response = webClient.get()
                    .uri(nseConfig.getHomeUrl())
                    .header("User-Agent", nseConfig.getUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .exchangeToMono(resp -> {
                        final List<String> cookies = resp.headers().asHttpHeaders()
                                .get(HttpHeaders.SET_COOKIE);
                        if (cookies != null && !cookies.isEmpty()) {
                            sessionCookie = String.join("; ", cookies);
                            logger.debug("NSE session cookie captured");
                        }
                        return resp.bodyToMono(String.class).thenReturn(true);
                    })
                    .onErrorResume(e -> {
                        logger.error("Failed to establish NSE session", e);
                        return Mono.just(false);
                    })
                    .block();
            if (Boolean.TRUE.equals(response)) {
                logger.info("NSE session established");
            }
        }
    }

    private OptionChainData fetchData() {
        try {
            return webClient.get()
                    .uri(nseConfig.getOptionChainUrl())
                    .header("User-Agent", nseConfig.getUserAgent())
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", nseConfig.getHomeUrl() + "/option-chain")
                    .header("Cookie", sessionCookie != null ? sessionCookie : "")
                    .retrieve()
                    .bodyToMono(OptionChainData.class)
                    .block();
        } catch (final Exception e) {
            logger.error("Failed to fetch NSE option chain data", e);
            sessionCookie = null;
            return null;
        }
    }

    public void resetSession() {
        this.sessionCookie = null;
    }
}
