package com.kite.trading.controller;

import com.kite.trading.entity.OiSnapshotEntity;
import com.kite.trading.repository.OiSnapshotRepository;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data")
public class DataExportController {

  private static final Logger logger = LoggerFactory.getLogger(DataExportController.class);

  private final OiSnapshotRepository repository;

  public DataExportController(final OiSnapshotRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/export/csv")
  public ResponseEntity<String> exportCsv(
      @RequestParam(required = false) final String from,
      @RequestParam(required = false) final String to) {
    try {
      final LocalDateTime start =
          from != null ? LocalDate.parse(from).atStartOfDay() : LocalDateTime.now().minusDays(30);
      final LocalDateTime end =
          to != null ? LocalDate.parse(to).atTime(LocalTime.MAX) : LocalDateTime.now();

      final List<OiSnapshotEntity> snapshots =
          repository.findByTimestampBetweenOrderByTimestampAsc(start, end);

      final String csv = buildCsv(snapshots);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=oi_snapshots.csv")
          .contentType(MediaType.TEXT_PLAIN)
          .body(csv);
    } catch (final Exception e) {
      logger.error("Failed to export CSV", e);
      return ResponseEntity.internalServerError().body("Export failed: " + e.getMessage());
    }
  }

  @GetMapping("/stats")
  public ResponseEntity<String> stats() {
    final LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
    final long count = repository.count();
    final long recentCount = repository.countByTimestampBetween(weekAgo, LocalDateTime.now());
    return ResponseEntity.ok(
        "Total snapshots: " + count + "\nSnapshots (last 7 days): " + recentCount + "\n");
  }

  private static String buildCsv(final List<OiSnapshotEntity> snapshots) {
    final StringWriter sw = new StringWriter();

    sw.write(
        "timestamp,indexName,underlyingValue,totalPeOi,totalCeOi,"
            + "totalPeOiChange,totalCeOiChange,pcr,largestPeOiStrike,largestCeOiStrike,"
            + "vix,indexOpen,indexHigh,indexLow\n");

    for (final OiSnapshotEntity s : snapshots) {
      sw.write(s.getTimestamp().toString());
      sw.write(",");
      sw.write(s.getIndexName());
      sw.write(",");
      sw.write(nullSafe(s.getUnderlyingValue()));
      sw.write(",");
      sw.write(nullSafe(s.getTotalPeOi()));
      sw.write(",");
      sw.write(nullSafe(s.getTotalCeOi()));
      sw.write(",");
      sw.write(nullSafe(s.getTotalPeOiChange()));
      sw.write(",");
      sw.write(nullSafe(s.getTotalCeOiChange()));
      sw.write(",");
      sw.write(nullSafe(s.getPcr()));
      sw.write(",");
      sw.write(nullSafe(s.getLargestPeOiStrike()));
      sw.write(",");
      sw.write(nullSafe(s.getLargestCeOiStrike()));
      sw.write(",");
      sw.write(nullSafe(s.getVix()));
      sw.write(",");
      sw.write(nullSafe(s.getIndexOpen()));
      sw.write(",");
      sw.write(nullSafe(s.getIndexHigh()));
      sw.write(",");
      sw.write(nullSafe(s.getIndexLow()));
      sw.write("\n");
    }

    return sw.toString();
  }

  private static String nullSafe(final Object value) {
    return value != null ? value.toString() : "";
  }
}
