# Kite Trading — OI-Based Intraday Monitor (Nifty & Sensex)

A Spring Boot application that monitors NSE Nifty/Sensex option chain data in real time, computes Put-Call Ratio (PCR) and Open Interest (OI) buildup, predicts intraday direction at 9:45 AM, auto-enters a credit spread / short strangle at 9:50 AM, and monitors positions with a multi-layered exit strategy — all via Telegram. Data is persisted to an embedded H2 database for optional LSTM model training.

## Features

- **Dual Index Routing** — Mon/Tue/Fri → Nifty, Wed/Thu → Sensex (auto-detected)
- **OI Analysis** — Fetches NSE option chain every 6 minutes, filters near ATM strikes
- **Direction Prediction** — Bullish/Bearish/Neutral based on PE vs CE OI change dominance (60% threshold)
- **Delta-Based Strike Selection** — Primary: Black-Scholes delta 0.15; fallback: ₹30-40 premium range
- **Position Sizing** — Based on ₹10L capital, 0.6% target (₹6K), 1% SL (₹10K), 16 lots
- **Multi-Layer Exit Strategy** — Hard stop (2× premium), trailing stop (0.5% pullback), profit target (80% decay), PCR shift, direction reversal, SuperTrend, OI surge, strike breach, time square-off (3:10 PM)
- **H2 Database Persistence** — All snapshots persisted with VIX + index OHLC, stored on host in `./data/`
- **CSV Data Export** — REST endpoint for ML training data download
- **LSTM Sidecar (optional)** — Python FastAPI sidecar for deep learning direction prediction; auto-trains daily at 4 PM
- **Telegram Notifications** — 9:45 AM prediction, OI updates (threshold-gated), exit alerts, direction changes, training results
- **Startup Health Check** — Verifies NSE connectivity and Telegram bot on startup
- **Docker** — Multi-stage build, JRE Alpine, non-root user, health check, sidecar container

## Prerequisites

- Java 21
- Gradle 9.x (bundled wrapper)
- Docker (optional, for containerised deployment)
- Telegram bot token (from [@BotFather](https://t.me/BotFather))
- Zerodha Kite API credentials (optional — for order placement endpoints)

## Quick Start

### 1. Clone & Configure

```bash
cp .env.example .env
```

Edit `.env` with your credentials:

```env
KITE_API_KEY=your-api-key
KITE_API_SECRET=your-api-secret
TELEGRAM_BOT_TOKEN=your-bot-token
TELEGRAM_CHAT_ID=your-chat-id
APP_BASE_URL=https://80.225.215.99
```

### 2. Run

```bash
./gradlew bootRun
```

The app starts on port 443 (HTTPS). Check the logs — you should see a Telegram startup message.

### 3. Docker

```bash
docker compose up --build
```

## Architecture

```
src/main/java/com/kite/trading/
├── KiteTradingApplication.java          # @EnableScheduling entry point
├── config/
│   ├── NseConfig.java                   # NSE API WebClient
│   ├── TelegramConfig.java              # Telegram API WebClient
│   ├── StartupHealthCheck.java          # Startup connectivity checks
│   ├── KiteConfig.java                  # Kite API credentials
│   ├── WebClientConfig.java             # Shared WebClient configuration
│   └── LoggingConfig.java               # HTTP log forwarding
├── controller/
│   ├── AuthController.java              # Kite Connect OAuth endpoints
│   ├── OptionChainController.java       # Option chain query endpoints
│   ├── PositionController.java          # Position query endpoints
│   └── DataExportController.java        # CSV export + stats
├── dto/
│   ├── OiDataSnapshot.java              # OI snapshot record (PCR, totals, buildup, premiums)
│   ├── OiAnalysisResult.java            # Prediction result + trade recommendation
│   ├── OptionChainData.java             # NSE API response DTO
│   ├── IndexQuote.java                  # Index OHLC quote
│   ├── LstmPredictionRequest.java       # LSTM sidecar request DTO
│   ├── LstmPredictionResponse.java      # LSTM sidecar response DTO
│   └── LstmTrainResponse.java           # LSTM training result DTO
├── entity/
│   └── OiSnapshotEntity.java            # JPA entity for H2 persistence
├── repository/
│   └── OiSnapshotRepository.java        # Spring Data JPA repository
├── scheduler/
│   └── IntradayOiScheduler.java         # 6-min fixed rate + cron reset/summary/train
├── exception/
│   └── GlobalExceptionHandler.java      # REST error handling
└── service/
    ├── OiAnalysisService.java           # Core OI analysis engine + H2 persist + LSTM integration
    ├── NseOptionChainClient.java        # NSE API client (primary)
    ├── YahooFinanceOptionChainClient.java  # Fallback option chain client
    ├── FallbackOptionChainClient.java    # @Primary orchestrator with retry
    ├── TelegramService.java             # Telegram messaging
    ├── LstmPredictionClient.java        # HTTP client to Python ML sidecar
    └── ZerodhaApiClient.java            # Kite trade API (order/quote)
```

```
ml-sidecar/                              # Python LSTM sidecar (optional)
├── main.py                              # FastAPI app (predict / train / health)
├── features.py                          # Feature engineering + label generation
├── train.py                             # LSTM model training + ONNX export
├── requirements.txt
├── Dockerfile
└── .dockerignore
```

### Data Flow

```
NSE API ──► NseOptionChainClient ──► OiAnalysisService ──► TelegramService
         (every 6 min)                  │                        │
                                        ├─ in-memory snapshots    ├─ 9:45 AM prediction
                                        ├─ H2 persistence         ├─ OI updates
                                        ├─ direction prediction   ├─ exit alerts
                                        ├─ exit signal check      ├─ direction changes
                                        └─ LSTM sidecar call      └─ training results
                                              │
                                              ▼
                                        ml-sidecar (Python)
                                        POST /predict → direction + confidence
                                        POST /train   → retrain from H2 data
```

## Telegram Messages

| Trigger | Content |
|---------|---------|
| **Startup** | Application initialized — NSE & Telegram OK |
| **9:45 AM Prediction** | Direction, confidence %, PCR, VIX, day range, largest PE/CE OI, open, strategy, strikes, trade recommendation with position sizing & OI reasoning |
| **Direction Change** | Updated prediction mid-day when OI buildup shifts |
| **OI Update** (6 min) | Current PCR, PE/CE OI + change, top buildup strikes (skipped if change < thresholds) |
| **Exit Signal** | Reason (HARD STOP / PROFIT TARGET / PCR SHIFT / DIRECTION REVERSAL / SUPERTREND / TIME SQOFF), confidence, exit fraction |
| **Early Warning** | Pre-confirmation alert when OI-based signal first detected |
| **Market Close** (4 PM) | Summary: 9:45 vs 3:30 index levels + movement |
| **LSTM Training** (4 PM) | Model retrained: accuracy, samples (only when enabled) |

### Thresholds (noise reduction)

- PCR must change by ≥ 0.05
- PE or CE OI must change by ≥ 5%
- Both must be exceeded for a periodic OI update notification

## Scheduler Schedule

| Time (IST) | Action |
|------------|--------|
| 9:00 AM | Reset daily state — clear in-memory snapshots, flags |
| 9:15 AM | `shouldRun()` enables — first OI snapshot of the day |
| 9:15 AM – 9:45 AM | OI snapshots recorded every 6 minutes |
| 9:45 AM | `notifyPrediction()` → direction prediction sent via Telegram |
| 9:50 AM | `markPositionEntered()` → auto-entry trigger |
| 9:50 AM – 3:30 PM | OI snapshots + direction change checks + exit monitoring every 6 minutes |
| 3:10 PM | Time-based square-off guard (inside exit assessment) |
| 4:00 PM | Market close summary + LSTM training trigger |
| Weekends | `shouldRun()` returns `false`, no activity |

## API Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/api/v1/auth/login-url` | Kite Connect login URL |
| GET | `/api/v1/auth/callback` | OAuth redirect handler |
| POST | `/api/v1/auth/session` | Exchange `request_token` for session |
| GET | `/api/v1/positions` | All active positions |
| GET | `/api/v1/positions/nifty/intraday` | NIFTY intraday positions |
| GET | `/api/v1/data/export/csv` | Download all snapshots as CSV (for ML training) |
| GET | `/api/v1/data/stats` | Snapshot counts (total + last 7 days) |
| GET | `/h2-console` | H2 database console (dev access) |
| GET | `/actuator/health` | Health check |

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `KITE_API_KEY` | Yes | — | Zerodha API key |
| `KITE_API_SECRET` | Yes | — | Zerodha API secret |
| `TELEGRAM_BOT_TOKEN` | Yes | — | Telegram bot token (from @BotFather) |
| `TELEGRAM_CHAT_ID` | Yes | — | Target chat/group ID |
| `ML_SIDECAR_ENABLED` | No | `false` | Enable LSTM sidecar integration |
| `ML_SIDECAR_URL` | No | `http://ml-sidecar:8000` | LSTM sidecar base URL |
| `NSE_OPTION_CHAIN_URL` | No | `https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY` | NSE API URL |
| `NSE_HOME_URL` | No | `https://www.nseindia.com` | NSE homepage (for cookie) |
| `KITE_BASE_URL` | No | `https://api.kite.trade` | Kite REST API base |
| `KITE_LOGIN_URL` | No | `https://kite.zerodha.com/connect/login` | Kite Connect login |
| `APP_BASE_URL` | No | `https://localhost:443` | Server base URL (Telegram login link) |
| `KITE_REDIRECT_URL` | No | `https://localhost:443/api/v1/auth/callback` | OAuth redirect |
| `SERVER_SSL_KEY_STORE` | No | `keystore.p12` | SSL keystore path |
| `SERVER_SSL_KEY_STORE_PASSWORD` | No | `changeme` | SSL keystore password |
| `SERVER_SSL_KEY_STORE_TYPE` | No | `PKCS12` | SSL keystore type |
| `SERVER_SSL_KEY_ALIAS` | No | `tomcat` | SSL certificate alias |
| `LOG_HTTP_URL` | No | *(empty)* | HTTP log forwarding endpoint |
| `LOG_HTTP_LEVEL` | No | `WARN` | Min level for forwarded logs |

## Development

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run locally
./gradlew bootRun

# Docker Compose (local dev)
docker compose up --build

# Build image manually
docker build -t sharadprsn/kite-trading:latest .
docker build -t sharadprsn/ml-sidecar:latest ./ml-sidecar

# Tag for your registry
docker tag kite-trading:latest sharadprsn/kite-trading:latest
docker tag kite-trading:latest sharadprsn/kite-trading:1.0.0

# Push to registry
docker push sharadprsn/kite-trading:latest
docker push sharadprsn/kite-trading:1.0.0

# Run from registry (after push)
docker run -d --name kite-trading -p 443:443 --env-file .env sharadprsn/kite-trading:latest
```

> **Note**: Replace `your-registry` with your actual Docker registry (e.g., `docker.io/username`, `ghcr.io/username`, or a private registry). Login first with `docker login` if required.

### Tests (36 tests)

| Test file | Tests | Covers |
|-----------|-------|--------|
| `OiAnalysisServiceTest` | 23 | PCR, ATM filter, direction prediction, thresholds, position lifecycle, reset, day-of-week routing, strikes, exit signals, delta calculation |
| `IntradayOiSchedulerTest` | 5 | shouldRun guard, reset, close summary, error handling |
| `StartupHealthCheckTest` | 4 | NSE/Telegram success and failure paths |
| `NseConnectivityTest` | 3 | NSE config URLs, option chain data validation |
| `KiteTradingApplicationTests` | 1 | Context load |

## Project Conventions

- Java 21, Spring Boot 3.4.x, Gradle 9.x
- Constructor injection, immutable DTOs (Java records)
- JUnit 5 + Mockito for tests (≥80% coverage target)
- Structured logging via SLF4J
- No `var` keyword, no heavy dependencies without explicit need

<details>
<summary><span style="color: red; font-weight: bold;">⚠️  FUTURE STEPS — Activate LSTM Model (click to expand)</span></summary>

<br>

Follow these steps **in order** after the app has been running for a few weeks.

### Step 1: Wait for data (2–4 weeks)
Snapshots are persisted to H2 every 6 minutes. Need **2000+ snapshots** (~2 weeks).

```bash
curl http://localhost:443/api/v1/data/stats
```
Target: `Total snapshots: 2000+`

### Step 2: Train the model for the first time
```bash
curl -X POST http://ml-sidecar:8000/train
```

<span style="color: green">Success:</span>
```json
{"status": "success", "val_accuracy": 0.62, ...}
```

<span style="color: red">Still waiting:</span>
```json
{"status": "skipped", "reason": "Insufficient data: ..."}
```

### Step 3: Verify model loaded
```bash
curl http://ml-sidecar:8000/health
```
Look for `"model_loaded": true`

### Step 4: Enable LSTM in production
```env
ML_SIDECAR_ENABLED=true
```
```bash
docker compose up -d
```

### Step 5: Confirm it's working
Check logs for `LSTM overrides (BULLISH, 82.3%)`.

At 4 PM you'll get a Telegram: `LSTM model retrained: accuracy=62.0%, samples=340`

### Troubleshooting

| Symptom | Fix |
|---------|-----|
| `/predict` returns 503 | Run Step 2 first |
| `/train` returns `skipped` | Check `/api/v1/data/stats` — need more data |
| Telegram says `error` | `docker logs ml-sidecar` |
| LSTM never overrides | Its confidence < rules — normal when model is weak |

</details>

## oiAnalysisService Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `PCR_BULLISH_THRESHOLD` | 1.2 | PCR above this is bullish |
| `PCR_BEARISH_THRESHOLD` | 0.8 | PCR below this is bearish |
| `STRIKE_INTERVAL_NIFTY` | 50 | Nifty strike spacing |
| `STRIKE_INTERVAL_SENSEX` | 100 | Sensex strike spacing |
| `LOT_SIZE_NIFTY` | 65 | Nifty lot size (incl. buffer) |
| `LOT_SIZE_SENSEX` | 20 | Sensex lot size (incl. buffer) |
| `DEPLOYED_CAPITAL` | ₹10,00,000 | Position sizing base |
| `TARGET_PCT` | 0.6% | Intraday profit target |
| `STOP_LOSS_PCT` | 1.0% | Max loss per trade |
| `EXIT_PCR_SHIFT` | 0.3 | Floor PCR shift threshold |
| `EXIT_OI_SURGE` | 50 | OI surge exit trigger (contracts) |
| `PCR_VOLATILITY_WINDOW` | 8 | Snapshot window for dynamic PCR threshold |
| `PCR_VOLATILITY_MULTIPLIER` | 2.0 | Multiplier for dynamic PCR threshold |
| `DIRECTION_ROLLING_WINDOW` | 3 | Snapshots for rolling direction |
| `CONFIRMATION_CONSECUTIVE` | 2 | Required confirmations for OI-based exits |
| `EXIT_COOLDOWN_MINUTES` | 15 | Cooldown after OI-based exit |
| `HARD_STOP_LOSS_PCT` | 1.0 | Hard stop % (triggers at 2× premium) |
| `TRAILING_STOP_PCT` | 0.5 | Trailing stop pullback % from best price |
| `AFTERNOON_THRESHOLD_MULTIPLIER` | 0.5 | PCR thresholds halved after 2:30 PM |
| `MIN_PCR_CHANGE_FOR_NOTIFICATION` | 0.05 | Min PCR change to send Telegram update |
| `MIN_OI_CHANGE_FRACTION` | 0.05 | Min OI change fraction (5%) |
| `NEAR_STRIKE_RANGE` | 5 | ATM ± 5 strikes for OI aggregation |
| `TOP_STRIKES_COUNT` | 5 | Top OI buildup strikes to track |
| `MARGIN_PER_LOT` | ₹60,000 | Margin required per lot |
| `SUPERTREND_PERIOD` | 3 | ATR period (15 min) |
| `SUPERTREND_MULTIPLIER` | 3.0 | ATR multiplier |
