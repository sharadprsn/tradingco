# Intraday OI (Open Interest) Trading Strategy

## Overview

This strategy analyzes Nifty option chain data to determine intraday market direction based on Open Interest (OI) changes. It runs on a 6-minute cycle during market hours (9:30 AM - 3:30 PM IST, Mon-Fri), computes a 10 AM market prediction, and monitors positions for exit signals with multiple loss-mitigation layers.

---

## Core Architecture

### Data Flow

```
NSE Option Chain API
        |
        v
  OptionChainClient (fetches raw data)
        |
        v
  OiAnalysisService (processes, analyzes, monitors)
        |
        v
  TelegramService (sends alerts)
```

### Scheduler (`IntradayOiScheduler`)

| Time | Event |
|------|-------|
| 9:00 AM | Daily reset — clear state, send initialization message |
| 9:05 AM | Send Kite login URL for authentication |
| 9:30-3:30 (every 6 min) | Fetch OI data, check for updates, send 10 AM prediction, check exit signals |
| 4:00 PM | Market close summary, reset state |

---

## OI Data Fetching (`fetchAndRecordOi`)

1. Fetch the full option chain from NSE/Kite API
2. Parse expiry dates
3. Filter strikes within ±5 strikes of ATM (250-point range for 50-point intervals)
4. Aggregate total PE and CE OI and OI changes for near-ATM strikes
5. Compute Put-Call Ratio (PCR) = Total PE OI / Total CE OI
6. Sort strikes by OI change (descending), keep top 5
7. Store as a timestamped `OiDataSnapshot`
8. Keep max 100 snapshots in memory
9. When a position is active, update highest/lowest price watermarks for trailing stop

### Key Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `STRIKE_INTERVAL` | 50 | Nifty strike spacing |
| `NEAR_STRIKE_RANGE` | 5 | Number of strikes above/below ATM to include |
| `TOP_STRIKES_COUNT` | 5 | Number of top OI buildup strikes to track |
| `PCR_BULLISH_THRESHOLD` | 1.2 | PCR above this is considered bullish |
| `PCR_BEARISH_THRESHOLD` | 0.8 | PCR below this is considered bearish |
| `OI_CHANGE_SIGNIFICANCE` | 20 | Minimum OI change considered significant |

---

## Market Prediction (`analyzeAndPredict`)

Runs at 10 AM (and on demand) to determine the day's directional bias.

### Direction Logic

Compare the first snapshot vs latest snapshot:

- **BULLISH**: PE OI change > CE OI change AND PE change > 60% of total OI change
- **BEARISH**: CE OI change > PE OI change AND CE change > 60% of total OI change
- **NEUTRAL**: Balanced OI buildup

### Confidence

- BULLISH/BEARISH: The dominant percentage (capped at 90%)
- NEUTRAL: 50%

### Strategy Mapping

| Direction | Strategy |
|-----------|----------|
| BULLISH | PUT CREDIT SPREAD |
| BEARISH | CALL CREDIT SPREAD |
| NEUTRAL | IRON CONDOR |

### Strike Selection

- **BULLISH**: Sell the first PE strike below ATM with top OI buildup (or ATM - 100), hedge 50 points below
- **BEARISH**: Sell the first CE strike above ATM with top OI buildup (or ATM + 100), hedge 50 points above
- **NEUTRAL**: Sell both sides' strongest OI buildup strikes, hedge outside

### Trade Recommendation Format

| Direction | Format |
|-----------|--------|
| BULLISH | `SELL {strike} PE (nearExpiry) \| HEDGE: BUY {hedge} PE (farExpiry)` |
| BEARISH | `SELL {strike} CE (nearExpiry) \| HEDGE: BUY {hedge} CE (farExpiry)` |
| NEUTRAL | `SELL {putStrike} PE & {callStrike} CE (nearExpiry) \| HEDGE: BUY {putHedge} PE & {callHedge} CE (farExpiry)` |

---

## Exit Strategy (`notifyExitIfNeeded`)

The exit system is a multi-layered assessment pipeline with three categories of protection:

1. **Price-based stops** (defensive) — hard stop-loss, trailing stop-loss, loss cap
2. **OI-based signals** (predictive) — PCR shift, direction reversal, SuperTrend reversal, OI surge
3. **Confidence scoring** — confirmation streaks, time-based tightening, price confirmation boost

### Exit Signal Types (`ExitSignal` enum)

| Signal | Category | Description |
|--------|----------|-------------|
| `NONE` | — | No exit warranted |
| `HARD_STOP` | Price-based | 1% adverse move against position → exit immediately |
| `TRAILING_STOP` | Price-based | 0.5% pullback from best price (when in profit) → exit immediately |
| `PCR_SHIFT` | OI-based | PCR has moved significantly since position entry |
| `DIRECTION_REVERSAL` | OI-based | Market direction inferred from OI has reversed |
| `SUPERTREND_REVERSAL` | OI-based | 15-min SuperTrend flipped direction |
| `OI_SURGE` | OI-based | Abnormal OI volume detected |

### Assessment Pipeline (`computeExitAssessment`)

The pipeline runs in this strict order — earlier checks take priority and bypass later checks:

```
1. Guard Checks
   - Position must be entered
   - At least 2 snapshots must exist
   - Entry snapshot must be available

2. HARD STOP CHECK (Price-Based — skips cooldown)
   - If entry direction is BULLISH and price dropped >= 1% from entry → HARD_STOP
   - If entry direction is BEARISH and price rose >= 1% from entry → HARD_STOP
   - Neutral direction: 1% move either way → HARD_STOP
   - Fires immediately, no confirmation needed

3. TRAILING STOP CHECK (Price-Based — skips cooldown)
   - If BULLISH and in profit: track highest price since entry
     If price pulls back >= 0.5% from that peak → TRAILING_STOP
   - If BEARISH and in profit: track lowest price since entry
     If price rallies >= 0.5% from that trough → TRAILING_STOP
   - Fires immediately, no confirmation needed

4. LOSS CAP CHECK (Price-Based — skips cooldown)
   - If estimated unrealized loss exceeds 2% → HARD_STOP
   - Loss is estimated relative to entry price and direction
   - Fires immediately, no confirmation needed

5. Cooldown Check
   - Only reached if none of the price-based stops triggered
   - If an OI-based exit fired within the last 15 minutes, return NONE
   - Hard stops do NOT set cooldown, allowing immediate re-entry

6. Afternoon Threshold Tightening
   - After 2:30 PM IST, the PCR threshold is halved (× 0.5)
   - Accounts for accelerated theta decay and fading intraday trends

7. Signal Detection (priority order)
   a. Dynamic PCR Shift → PCR_SHIFT
   b. Rolling Direction Reversal → DIRECTION_REVERSAL
   c. SuperTrend Reversal → SUPERTREND_REVERSAL
   d. OI Volume Surge → OI_SURGE
   e. No signal → reset streak, return NONE

8. Strong Signal Fast-Track
   - If PCR_SHIFT + DIRECTION_REVERSAL fire together → requires only 1 confirmation
   - If PCR_SHIFT ratio > 2× threshold → requires only 1 confirmation
   - Otherwise → requires 2 confirmations (standard)

9. Confirmation Lag
   - First detection: log, send EARLY WARNING via Telegram, set streak=1, return NONE
   - Consecutive same signal: increment streak
   - If streak < required: return NONE (waiting for confirmation)
   - If streak >= required: proceed (confirmed signal)

10. Confidence Computation
    - PCR_SHIFT: 30% + (ratio × 40%), capped at 80%
    - DIRECTION_REVERSAL: 70%
    - SUPERTREND_REVERSAL: 65%
    - OI_SURGE: 55%
    - Boost: +15% if multiple signals, +10% if price confirmed, +10% after 2:30 PM, +10% if loss > 0.5%
    - Overall cap: 95%

11. Exit Fraction (scaling)
    - HARD_STOP / TRAILING_STOP: exit 100%
    - PCR_SHIFT with ratio < 1.5×: exit 50% of position
    - PCR_SHIFT with ratio >= 1.5×: exit 100%
    - SUPERTREND_REVERSAL: exit 50%
    - All other signals: exit 100%

12. Fire Exit
    - Send Telegram alert via `buildExitReport`
    - For OI-based exits: record `lastExitFiredAt` for cooldown
    - For price-based exits: skip cooldown (allows immediate re-entry)
    - Reset confirmation state, early warning flag
```

### Enhanced Detection Methods

#### Dynamic PCR Threshold (`computeDynamicPcrThreshold`)

Instead of a static 0.3 threshold, compute the average absolute PCR change over the last 8 snapshot-to-snapshot transitions, multiply by 2:

```
threshold = max(avg(|ΔPCR|) × 2, 0.1, EXIT_PCR_SHIFT)
```

This adapts to market volatility — quieter markets get tighter thresholds, volatile markets get wider thresholds.

#### Time Decay Dampening (`getTimeDecayFactor`)

Within 2 days of expiry, PCR shifts become less reliable due to theta decay. The threshold is multiplied by 1.5:

```
effectiveThreshold = dynamicThreshold × (daysToExpiry <= 2 ? 1.5 : 1.0)
```

#### Rolling Window Direction (`computeRollingDirection`)

Instead of computing direction from a single snapshot's OI change, sum the OI changes across the last 3 snapshots to smooth out noise:

```
sumPeChange = Σ totalPeOiChange(last 3 snapshots)
sumCeChange = Σ totalCeOiChange(last 3 snapshots)
```

Apply the same >60% dominance rule on the aggregated values.

#### OI Surge Detection

If the total absolute OI change (|PE change| + |CE change|) in a single snapshot exceeds 50 contracts, flag as an OI surge — indicating aggressive positioning.

#### SuperTrend Reversal (`checkSuperTrendReversal`)

A 15-min SuperTrend (3 snapshots) using ATR-based bands. When the trend direction flips, it triggers `SUPERTREND_REVERSAL` — a 50% partial exit signal.

#### Hard Stop-Loss (`isHardStopTriggered`)

Exits the position immediately if the underlying moves 1% against the entry direction. This is the primary loss cap mechanism:

- BULLISH entry: stop if price drops >= 1% below entry
- BEARISH entry: stop if price rises >= 1% above entry
- Skips cooldown, fires without confirmation

#### Trailing Stop-Loss (`isTrailingStopTriggered`)

Once the position is in profit, tracks the best price (highest for BULLISH, lowest for BEARISH). If the price pulls back 0.5% from that extreme, exits to lock in gains:

- BULLISH: stop if current price < peak × (1 - 0.005)
- BEARISH: stop if current price > trough × (1 + 0.005)
- Only fires when in profit (current price better than entry)

#### Loss Cap (`isLossCapExceeded`)

If the estimated unrealized loss exceeds 2% of entry, triggers a hard exit. Estimated loss is calculated relative to direction:

- BULLISH: loss % = (entry - current) / entry × 100
- BEARISH: loss % = (current - entry) / entry × 100
- If current price is favorable (profit), loss is 0%

#### Early Warning (`sendEarlyWarning`)

On the first detection of any OI-based signal (before confirmation), sends a Telegram alert:

> ⚠️ EARLY WARNING: PCR shift detected (pending confirmation). Monitor closely.
> Estimated loss: 0.8%
> Current Nifty: 24150

Only sent once per entry (tracked via `earlyWarningSent` flag).

#### Afternoon Tightening

After 2:30 PM IST, all PCR thresholds are tightened by 50% (`AFTERNOON_THRESHOLD_MULTIPLIER = 0.5`) to account for:
- Accelerated theta decay in options
- Weakening intraday trends
- Reduced time for trade recovery

#### Price Confirmation (`isPriceConfirmed`)

Changed from a gate (blocking exits) to a confidence booster (+10). The 0.5% price-move requirement is still computed but no longer suppresses signals — it only adds confidence.

#### Cooldown (`isInCooldown`)

- Reduced from 30 to 15 minutes
- Only applies to OI-based exits (PCR shift, direction reversal, SuperTrend, OI surge)
- Hard stops and trailing stops skip cooldown entirely

### Position Lifecycle

| Method | Effect |
|--------|--------|
| `markPositionEntered()` | Sets `positionEntered=true`, records entry snapshot, entry price, computed entry direction, initializes price watermarks (`highestPriceSinceEntry` / `lowestPriceSinceEntry`), resets `earlyWarningSent` |
| `markPositionExited()` | Clears `positionEntered`, clears entry snapshot, resets watermarks and early warning flag |
| `reset()` | Full state reset including watermarks and early warning flag (daily) |

### Exit Alert Format

```
⚠️ EXIT SIGNAL DETECTED ⚠️

HARD STOP-LOSS triggered. Price moved beyond stop-loss threshold. EXIT IMMEDIATELY.
Confidence: 95%

Current PCR: 1.15
Current Nifty: 23900.00
```

Or for OI-based exits with new recommendation:

```
⚠️ EXIT SIGNAL DETECTED ⚠️

PCR has shifted significantly since entry.
Confidence: 72%
Suggested Exit: 50% of position

Current PCR: 1.15
Current Nifty: 24350.00

🔄 New Recommendation:
🟢 Direction: BULLISH (75%)
Strategy: PUT CREDIT SPREAD
SELL 24300 PE (05-JUN) | HEDGE: BUY 24200 PE (12-JUN)
```

---

## Change Notification (`notifyOiUpdate`)

OI updates are sent only when significant changes are detected:

- PCR change > 0.05
- PE or CE OI change > 5% of total open interest

---

## Configuration Parameters

### Entry/Prediction Constants

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `PREDICTION_TIME` | 10:00 AM IST | Time for daily prediction |
| `PCR_BULLISH_THRESHOLD` | 1.2 | PCR above this is bullish |
| `PCR_BEARISH_THRESHOLD` | 0.8 | PCR below this is bearish |
| `TOP_STRIKES_COUNT` | 5 | Top OI buildup strikes to track |
| `STRIKE_INTERVAL` | 50 | Nifty strike spacing |

### Exit Strategy Constants

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `EXIT_PCR_SHIFT` | 0.3 | Floor for PCR shift threshold |
| `EXIT_OI_SURGE` | 50 | OI surge trigger (contracts) |
| `CONFIRMATION_CONSECUTIVE` | 2 | Standard consecutive checks needed |
| `STRONG_SIGNAL_CONFIRMATION_REQUIRED` | 1 | Fast-track when multiple signals combine |
| `PCR_VOLATILITY_WINDOW` | 8 | Snapshot window for avg ΔPCR |
| `PCR_VOLATILITY_MULTIPLIER` | 2.0 | Multiplier for dynamic threshold |
| `DIRECTION_ROLLING_WINDOW` | 3 | Snapshots for rolling direction |
| `SUPERTREND_PERIOD` | 3 | Snapshots for SuperTrend (15 min) |
| `SUPERTREND_MULTIPLIER` | 3.0 | ATR multiplier for SuperTrend bands |
| `EXIT_COOLDOWN_MINUTES` | 15 | Cooldown after OI-based exit (reduced from 30) |
| `EXIT_CONFIDENCE_THRESHOLD` | 50 | Minimum confidence % to fire |
| `PRICE_CONFIRMATION_PCT` | 0.5 | Min price move (now confidence booster, not gate) |
| `DAYS_TO_EXPIRY_DAMPENING` | 2 | Expiry proximity threshold |
| `TIME_DECAY_FACTOR` | 1.5 | PCR threshold multiplier near expiry |
| `HARD_STOP_LOSS_PCT` | 1.0 | Hard stop-loss % from entry |
| `TRAILING_STOP_PCT` | 0.5 | Trailing stop pullback % from peak |
| `LOSS_CAP_PCT` | 2.0 | Maximum acceptable loss % |
| `AFTERNOON_THRESHOLD_MULTIPLIER` | 0.5 | PCR threshold tightener after 2:30 PM |

### Scheduler Constants

| Parameter | Value |
|-----------|-------|
| `MARKET_START` | 9:30 AM IST |
| `PREDICTION_TIME` | 10:00 AM IST |
| `MARKET_CLOSE` | 3:30 PM IST |
| `SIX_MINUTES_MS` | 360,000 ms |

---

## Key Data Structures

### `OiDataSnapshot` (record)
```
timestamp: LocalDateTime
underlyingValue: BigDecimal
totalPeOi: BigDecimal
totalCeOi: BigDecimal
totalPeOiChange: BigDecimal
totalCeOiChange: BigDecimal
pcr: BigDecimal
topOiBuildUp: List<OiStrikeInfo>
```

### `OiStrikeInfo` (record, nested in `OiDataSnapshot`)
```
strikePrice: BigDecimal
optionType: String ("PE" or "CE")
openInterest: BigDecimal
changeInOi: BigDecimal
pchangeInOi: BigDecimal
```

### `OiAnalysisResult` (record)
```
direction: String ("BULLISH" | "BEARISH" | "NEUTRAL")
confidence: BigDecimal
pcr: BigDecimal
suggestedStrategy: String
suggestedStrikes: List<BigDecimal>
reasoning: String
tradeRecommendation: String
```

### `ExitSignal` (enum)
```
NONE, PCR_SHIFT, DIRECTION_REVERSAL, SUPERTREND_REVERSAL, OI_SURGE,
HARD_STOP, TRAILING_STOP
```

### `ExitAssessment` (record, nested in `OiAnalysisService`)
```
signal: ExitSignal
confidence: BigDecimal
exitFraction: BigDecimal  (0.0-1.0, e.g., 0.5 = exit 50%)
```

---

## Usage

### Entry flow:
1. User decides to enter a trade based on the 10 AM prediction (or other analysis)
2. Calls `oiAnalysisService.markPositionEntered()` to begin exit monitoring
3. Scheduler checks `isPositionEntered()` every 6 minutes and runs exit assessment

### Exit flow:
1. Scheduler calls `notifyExitIfNeeded()` every 6 minutes
2. Price-based stops (hard stop, trailing stop, loss cap) are checked first — fire immediately
3. OI-based signals go through confirmation lag and cooldown
4. If a confirmed exit signal is detected, a Telegram alert is sent
5. User manually exits the position and calls `markPositionExited()`

### Monitoring:
- OI updates are sent automatically on significant changes
- Early warning alerts sent on first OI signal detection (before confirmation)
- Exit alerts include current market data and a fresh recommendation
- Market close summary is sent at 4 PM

---

## Improvement History

### Initial Exit Strategy
- Static PCR threshold (0.3)
- Single-snapshot direction comparison
- No OI surge detection
- No confirmation/cooldown/weighting

### Enhanced Exit Strategy (previous)
- Dynamic PCR threshold based on recent volatility
- Time-decay dampening near expiry
- Rolling window direction computation
- OI surge detection (EXIT_OI_SURGE)
- Price confirmation gate (0.5% minimum move)
- 30-minute cooldown after exit
- Signal weighting with confidence scores
- Trailing/scaling exit (50% or 100% based on severity)
- Confirmation lag (2 consecutive checks required)

### Loss Minimization Improvements (current)
- **Hard stop-loss (1%)**: Price-based, checked before OI signals, skips cooldown
- **Trailing stop-loss (0.5%)**: Locks in profits, only fires when in profit
- **Loss cap (2%)**: Maximum acceptable drawdown before forced exit
- **Price confirmation → confidence booster**: No longer blocks exits, adds +10 confidence
- **Early warning alerts**: Telegram on first OI signal detection (pre-confirmation)
- **Strong signal fast-track**: Combined signals skip to 1 confirmation
- **SuperTrend reversal**: New exit signal type (50% partial exit)
- **Afternoon tightening**: PCR thresholds halved after 2:30 PM
- **Cooldown reduced**: 30 → 15 minutes, skipped entirely for price-based stops
