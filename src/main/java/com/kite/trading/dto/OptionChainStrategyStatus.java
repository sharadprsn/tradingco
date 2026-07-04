package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OptionChainStrategyStatus(
    @JsonProperty("active") boolean active,
    @JsonProperty("phase") String phase,
    @JsonProperty("underlyingValue") BigDecimal underlyingValue,
    @JsonProperty("entryTime") LocalDateTime entryTime,
    @JsonProperty("lastEvaluationTime") LocalDateTime lastEvaluationTime,
    @JsonProperty("strategyType") String strategyType,
    @JsonProperty("legs") List<LegInfo> legs,
    @JsonProperty("totalPremium") BigDecimal totalPremium,
    @JsonProperty("maxLoss") BigDecimal maxLoss,
    @JsonProperty("currentPnl") BigDecimal currentPnl,
    @JsonProperty("ivAnalysis") IvAnalysis ivAnalysis
) {
    public record LegInfo(
        @JsonProperty("strike") BigDecimal strike,
        @JsonProperty("type") String type,
        @JsonProperty("action") String action,
        @JsonProperty("premium") BigDecimal premium,
        @JsonProperty("iv") BigDecimal iv,
        @JsonProperty("orderId") String orderId
    ) {}

    public record IvAnalysis(
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("nearestPutIv") BigDecimal nearestPutIv,
        @JsonProperty("nearestCallIv") BigDecimal nearestCallIv,
        @JsonProperty("putSkew") BigDecimal putSkew,
        @JsonProperty("callSkew") BigDecimal callSkew,
        @JsonProperty("interpretation") String interpretation
    ) {}
}
