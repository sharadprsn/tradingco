package com.kite.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "nse")
public class NseConfig {

    private String optionChainUrl = "https://www.nseindia.com/api/option-chain-v3?type=Indices&symbol=NIFTY";
    private String contractInfoUrl = "https://www.nseindia.com/api/option-chain-contract-info?symbol=NIFTY";
    private String homeUrl = "https://www.nseindia.com/option-chain";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private String indexQuoteUrl = "https://www.nseindia.com/api/quote-indices?indices=NIFTY%2050";

    public NseConfig() {
    }

    public String getOptionChainUrl() {
        return optionChainUrl;
    }

    public void setOptionChainUrl(final String optionChainUrl) {
        this.optionChainUrl = optionChainUrl;
    }

    public String getHomeUrl() {
        return homeUrl;
    }

    public void setHomeUrl(final String homeUrl) {
        this.homeUrl = homeUrl;
    }

    public String getContractInfoUrl() {
        return contractInfoUrl;
    }

    public void setContractInfoUrl(final String contractInfoUrl) {
        this.contractInfoUrl = contractInfoUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(final String userAgent) {
        this.userAgent = userAgent;
    }

    public String getIndexQuoteUrl() {
        return indexQuoteUrl;
    }

    public void setIndexQuoteUrl(final String indexQuoteUrl) {
        this.indexQuoteUrl = indexQuoteUrl;
    }
}
