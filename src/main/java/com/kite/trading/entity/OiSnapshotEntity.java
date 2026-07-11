package com.kite.trading.entity;

import com.kite.trading.dto.OiDataSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "oi_snapshots")
public class OiSnapshotEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private LocalDateTime timestamp;

  @Column(nullable = false)
  private String indexName;

  @Column(nullable = false, precision = 20, scale = 4)
  private BigDecimal underlyingValue;

  @Column(nullable = false, precision = 20, scale = 4)
  private BigDecimal totalPeOi;

  @Column(nullable = false, precision = 20, scale = 4)
  private BigDecimal totalCeOi;

  @Column(nullable = false, precision = 20, scale = 4)
  private BigDecimal totalPeOiChange;

  @Column(nullable = false, precision = 20, scale = 4)
  private BigDecimal totalCeOiChange;

  @Column(nullable = false, precision = 10, scale = 4)
  private BigDecimal pcr;

  @Column(precision = 20, scale = 4)
  private BigDecimal largestPeOiStrike;

  @Column(precision = 20, scale = 4)
  private BigDecimal largestCeOiStrike;

  @Column(precision = 10, scale = 4)
  private BigDecimal vix;

  @Column(precision = 20, scale = 4)
  private BigDecimal indexOpen;

  @Column(precision = 20, scale = 4)
  private BigDecimal indexHigh;

  @Column(precision = 20, scale = 4)
  private BigDecimal indexLow;

  @Column(columnDefinition = "CLOB")
  private String topOiBuildUpJson;

  @Column(columnDefinition = "CLOB")
  private String strikePremiumsJson;

  @Column(precision = 10, scale = 4)
  private BigDecimal marketSentiment;

  public OiSnapshotEntity() {}

  public OiSnapshotEntity(
      final LocalDateTime timestamp,
      final String indexName,
      final BigDecimal underlyingValue,
      final BigDecimal totalPeOi,
      final BigDecimal totalCeOi,
      final BigDecimal totalPeOiChange,
      final BigDecimal totalCeOiChange,
      final BigDecimal pcr,
      final BigDecimal largestPeOiStrike,
      final BigDecimal largestCeOiStrike,
      final BigDecimal vix,
      final BigDecimal indexOpen,
      final BigDecimal indexHigh,
      final BigDecimal indexLow,
      final String topOiBuildUpJson,
      final String strikePremiumsJson,
      final BigDecimal marketSentiment) {
    this.timestamp = timestamp;
    this.indexName = indexName;
    this.underlyingValue = underlyingValue;
    this.totalPeOi = totalPeOi;
    this.totalCeOi = totalCeOi;
    this.totalPeOiChange = totalPeOiChange;
    this.totalCeOiChange = totalCeOiChange;
    this.pcr = pcr;
    this.largestPeOiStrike = largestPeOiStrike;
    this.largestCeOiStrike = largestCeOiStrike;
    this.vix = vix;
    this.indexOpen = indexOpen;
    this.indexHigh = indexHigh;
    this.indexLow = indexLow;
    this.topOiBuildUpJson = topOiBuildUpJson;
    this.strikePremiumsJson = strikePremiumsJson;
    this.marketSentiment = marketSentiment;
  }

  public static OiSnapshotEntity fromSnapshot(
      final OiDataSnapshot snapshot,
      final String indexName,
      final BigDecimal vix,
      final BigDecimal indexOpen,
      final BigDecimal indexHigh,
      final BigDecimal indexLow,
      final String topOiBuildUpJson,
      final String strikePremiumsJson) {
    return new OiSnapshotEntity(
        snapshot.timestamp(),
        indexName,
        snapshot.underlyingValue(),
        snapshot.totalPeOi(),
        snapshot.totalCeOi(),
        snapshot.totalPeOiChange(),
        snapshot.totalCeOiChange(),
        snapshot.pcr(),
        snapshot.largestPeOiStrike(),
        snapshot.largestCeOiStrike(),
        vix,
        indexOpen,
        indexHigh,
        indexLow,
        topOiBuildUpJson,
        strikePremiumsJson,
        snapshot.marketSentiment());
  }

  public Long getId() {
    return id;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public String getIndexName() {
    return indexName;
  }

  public BigDecimal getUnderlyingValue() {
    return underlyingValue;
  }

  public BigDecimal getTotalPeOi() {
    return totalPeOi;
  }

  public BigDecimal getTotalCeOi() {
    return totalCeOi;
  }

  public BigDecimal getTotalPeOiChange() {
    return totalPeOiChange;
  }

  public BigDecimal getTotalCeOiChange() {
    return totalCeOiChange;
  }

  public BigDecimal getPcr() {
    return pcr;
  }

  public BigDecimal getLargestPeOiStrike() {
    return largestPeOiStrike;
  }

  public BigDecimal getLargestCeOiStrike() {
    return largestCeOiStrike;
  }

  public BigDecimal getVix() {
    return vix;
  }

  public BigDecimal getIndexOpen() {
    return indexOpen;
  }

  public BigDecimal getIndexHigh() {
    return indexHigh;
  }

  public BigDecimal getIndexLow() {
    return indexLow;
  }

  public String getTopOiBuildUpJson() {
    return topOiBuildUpJson;
  }

  public String getStrikePremiumsJson() {
    return strikePremiumsJson;
  }

  public BigDecimal getMarketSentiment() {
    return marketSentiment;
  }

  public OiDataSnapshot toSnapshot() {
    return new OiDataSnapshot(
        timestamp,
        underlyingValue,
        totalPeOi,
        totalCeOi,
        totalPeOiChange,
        totalCeOiChange,
        pcr,
        List.of(),
        largestPeOiStrike,
        largestCeOiStrike,
        List.of(),
        marketSentiment);
  }
}
