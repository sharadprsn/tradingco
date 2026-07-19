package com.kite.trading.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class BacktestReport {

  private final LocalDate from;
  private final LocalDate to;
  private final List<BacktestSignalRow> signals = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private final List<String> vixSkips = new ArrayList<>();

  BacktestReport(final LocalDate from, final LocalDate to) {
    this.from = from;
    this.to = to;
  }

  void recordSignal(
      final String index,
      final LocalDate day,
      final String direction,
      final BigDecimal spot,
      final BigDecimal pdh,
      final BigDecimal pdl,
      final BigDecimal orHigh,
      final BigDecimal orLow,
      final BacktestTrade trade) {
    signals.add(new BacktestSignalRow(index, day, direction, spot, pdh, pdl, orHigh, orLow, trade));
  }

  void recordVixSkip(final String index, final LocalDate day, final BigDecimal vix) {
    vixSkips.add(index + " " + day + " VIX=" + vix + " (gate, no trade)");
  }

  void addNote(final String note) {
    notes.add(note);
  }

  void finalizeReport() {}

  int totalSignals() {
    return signals.size();
  }

  long wins() {
    return signals.stream()
        .filter(s -> s.trade().outcome().contains("WIN") || s.trade().outcome().equals("TARGET"))
        .count();
  }

  long losses() {
    return signals.stream()
        .filter(s -> s.trade().outcome().contains("LOSS") || s.trade().outcome().equals("STOP"))
        .count();
  }

  public String summary() {
    final long wins = wins();
    final long losses = losses();
    final int total = signals.size();
    final BigDecimal totalPnl =
        signals.stream().map(s -> s.trade().pnlPct()).reduce(BigDecimal.ZERO, BigDecimal::add);
    final String winRate = total == 0 ? "n/a" : (wins * 100 / total) + "%";
    final StringBuilder sb = new StringBuilder();
    sb.append("=== Sniper Backtest ").append(from).append(" -> ").append(to).append(" ===\n");
    sb.append("Total signals: ").append(total).append("\n");
    sb.append("Wins: ").append(wins).append("  Losses: ").append(losses).append("\n");
    sb.append("Win rate: ").append(winRate).append("\n");
    sb.append("Net spot P&L%: ").append(totalPnl).append("\n");
    sb.append("VIX-gate skipped days: ").append(vixSkips.size()).append("\n");
    sb.append("Notes (data gaps): ").append(notes.size()).append("\n");
    for (final String n : notes) {
      sb.append("  - ").append(n).append("\n");
    }
    return sb.toString();
  }

  List<BacktestSignalRow> signals() {
    return List.copyOf(signals);
  }

  record BacktestSignalRow(
      String index,
      LocalDate day,
      String direction,
      BigDecimal spot,
      BigDecimal pdh,
      BigDecimal pdl,
      BigDecimal orHigh,
      BigDecimal orLow,
      BacktestTrade trade) {}
}
