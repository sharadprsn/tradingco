# Intraday OI (Open Interest) Trading Strategy

## Overview

This strategy analyzes Nifty/BankNifty option chain data to determine intraday market direction based on Open Interest (OI) changes. It runs on a 6-minute cycle during market hours (9:30 AM - 3:30 PM IST, Mon-Fri), computes a 10 AM market prediction, and monitors positions for exit signals.

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

The exit system is the most sophisticated component. It uses a multi-layered assessment pipeline.

### Exit Signal Types (`ExitSignal` enum)

| Signal | Description |
|--------|-------------|
| `NONE` | No exit warranted |
| `PCR_SHIFT` | PCR has moved significantly since position entry |
| `DIRECTION_REVERSAL` | Market direction inferred from OI has reversed |
| `OI_SURGE` | Abnormal OI volume detected |

### Assessment Pipeline (`computeExitAssessment`)

The pipeline runs in this order:

```
1. Guard Checks
   - Position must be entered
   - At least 2 snapshots must exist
   - Entry snapshot must be available

2. Cooldown Check
   - If an exit was fired within the last 30 minutes, return NONE

3. Signal Detection (priority order)
   a. Dynamic PCR Shift → PCR_SHIFT
   b. Rolling Direction Reversal → DIRECTION_REVERSAL
   c. OI Volume Surge → OI_SURGE
   d. No signal → reset streak, return NONE

4. Confirmation Lag
   - First detection: log, set streak=1, return NONE
   - Consecutive same signal: increment streak
   - If streak < 2: return NONE (waiting for confirmation)
   - If streak >= 2: proceed (confirmed signal)

5. Price Confirmation
   - Check if underlying price has moved at least 0.5% from entry
   - If not, suppress signal (return NONE)

6. Confidence Computation
   - PCR_SHIFT: 30% + (ratio × 40%), capped at 80%
   - DIRECTION_REVERSAL: 70%
   - OI_SURGE: 55%
   - Boost: +15% if multiple signals fire simultaneously
   - Overall cap: 95%

7. Exit Fraction (scaling)
   - PCR_SHIFT with ratio < 1.5x: exit 50% of position
   - PCR_SHIFT with ratio >= 1.5x: exit 100%
   - All other signals: exit 100%

8. Fire Exit
   - Send Telegram alert via `buildExitReport`
   - Record `lastExitFiredAt` for cooldown
   - Reset confirmation state
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

#### Price Confirmation (`isPriceConfirmed`)

Require the underlying price to have moved at least 0.5% from the entry price before honoring any exit signal. Prevents exits on OI changes that lack price follow-through.

#### Cooldown (`isInCooldown`)

After any exit signal fires, suppress all further exit signals for 30 minutes to prevent alert fatigue and allow reassessment.

### Position Lifecycle

| Method | Effect |
|--------|--------|
| `markPositionEntered()` | Sets `positionEntered=true`, records entry snapshot, entry price, and computed entry direction |
| `markPositionExited()` | Clears `positionEntered`, clears entry snapshot |
| `reset()` | Full state reset (daily) |

### Exit Alert Format

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

| Parameter | File Location | Value |
|-----------|---------------|-------|
| `PREDICTION_TIME` | `OiAnalysisService.java:37` | 10:00 AM IST |
| `PCR_BULLISH_THRESHOLD` | `OiAnalysisService.java:38` | 1.2 |
| `PCR_BEARISH_THRESHOLD` | `OiAnalysisService.java:39` | 0.8 |
| `TOP_STRIKES_COUNT` | `OiAnalysisService.java:41` | 5 |
| `STRIKE_INTERVAL` | `OiAnalysisService.java:43` | 50 |

### Exit Strategy Constants

| Parameter | File Location | Value | Purpose |
|-----------|---------------|-------|---------|
| `EXIT_PCR_SHIFT` | `OiAnalysisService.java:44` | 0.3 | Floor for PCR shift threshold |
| `EXIT_OI_SURGE` | `OiAnalysisService.java:45` | 50 | OI surge trigger (contracts) |
| `CONFIRMATION_CONSECUTIVE` | `OiAnalysisService.java:50` | 2 | Consecutive checks needed |
| `PCR_VOLATILITY_WINDOW` | `OiAnalysisService.java:51` | 8 | Snapshot window for avg ΔPCR |
| `PCR_VOLATILITY_MULTIPLIER` | `OiAnalysisService.java:52` | 2.0 | Multiplier for dynamic threshold |
| `DIRECTION_ROLLING_WINDOW` | `OiAnalysisService.java:53` | 3 | Snapshots for rolling direction |
| `EXIT_COOLDOWN_MINUTES` | `OiAnalysisService.java:54` | 30 | Cooldown after exit fires |
| `EXIT_CONFIDENCE_THRESHOLD` | `OiAnalysisService.java:55` | 50 | Minimum confidence % to fire |
| `PRICE_CONFIRMATION_PCT` | `OiAnalysisService.java:56` | 0.5 | Min price move to confirm |
| `DAYS_TO_EXPIRY_DAMPENING` | `OiAnalysisService.java:57` | 2 | Expiry proximity threshold |
| `TIME_DECAY_FACTOR` | `OiAnalysisService.java:58` | 1.5 | PCR threshold multiplier near expiry |

### Scheduler Constants

| Parameter | File Location | Value |
|-----------|---------------|-------|
| `MARKET_START` | `IntradayOiScheduler.java:21` | 9:30 AM IST |
| `PREDICTION_TIME` | `IntradayOiScheduler.java:22` | 10:00 AM IST |
| `MARKET_CLOSE` | `IntradayOiScheduler.java:23` | 3:30 PM IST |
| `SIX_MINUTES_MS` | `IntradayOiScheduler.java:24` | 360,000 ms |

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
NONE, PCR_SHIFT, DIRECTION_REVERSAL, OI_SURGE
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
2. If a confirmed exit signal is detected, a Telegram alert is sent
3. User manually exits the position and calls `markPositionExited()`

### Monitoring:
- OI updates are sent automatically on significant changes
- Exit alerts include current market data and a fresh recommendation
- Market close summary is sent at 4 PM

---

## Improvement History

### Initial Exit Strategy
- Static PCR threshold (0.3)
- Single-snapshot direction comparison
- No OI surge detection
- No confirmation/cooldown/weighting

### Enhanced Exit Strategy (current)
- Dynamic PCR threshold based on recent volatility
- Time-decay dampening near expiry
- Rolling window direction computation
- OI surge detection (EXIT_OI_SURGE)
- Price confirmation (0.5% minimum move)
- 30-minute cooldown after exit
- Signal weighting with confidence scores
- Trailing/scaling exit (50% or 100% based on severity)
- Confirmation lag (2 consecutive checks required)
