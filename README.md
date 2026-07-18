# Kite Trading — Intraday Strategies (Vande Bharat & Multi-TF RSI)

A Spring Boot application that runs two intraday trading strategies on NSE data, pushing signals and alerts to Telegram.

## Strategies

### 1. Vande Bharat — F&O Stock Breakout
Intraday breakout strategy on F&O stocks using PDH/PDL (Previous Day High/Low) levels:

- **Pre-market scan** (9:10 AM) — Fetches NSE pre-open data, selects top 10 F&O stocks by % change, loads PDH/PDL from bhavcopy
- **Breakout detection** — Price breaks above PDH (LONG) or below PDL (SHORT)
- **Inside candle filter** — Waits for a candle within the breakout candle's range with lower volume
- **Entry trigger** — Price breaks above inside-candle high (LONG) or below inside-candle low (SHORT) with higher volume
- **Position sizing** — Risk-based (0.5% of ₹10L capital per trade)
- **Exit** — Partial at 1:2 RR (50% book), trailing stop, EMA 10 crossover
- **Schedule** — Every 5 min, 9:30 AM – 3:30 PM

### 2. Multi-TF RSI Nifty Option
Nifty index options strategy using multi-timeframe RSI divergence and candlestick patterns:

- **Timeframe** — 5-min and 15-min candles built from 1-min Nifty index ticks
- **RSI (Wilder's smoothing)** — 14-period RSI on both timeframes, compared against 20-period SMA
- **Entry** — Both 5m and 15m RSI above SMA + bullish candlestick pattern → buy CE; both below SMA + bearish pattern → buy PE
- **Strike selection** — ATM ± 100 range, premium ₹60–100
- **Exit** — RSI crossover (5m RSI crosses below/above SMA), or timeframe conflict, or 11:30 AM time square-off
- **Max 1 trade per day**

### 2. Vande Bharat — F&O Stock Breakout
Intraday breakout strategy on F&O stocks using PDH/PDL (Previous Day High/Low):

- **Pre-market scan** (9:10 AM) — Fetches NSE pre-open data, selects top 10 F&O stocks by % change, loads PDH/PDL from bhavcopy
- **Breakout detection** — Price breaks above PDH (LONG) or below PDL (SHORT)
- **Inside candle filter** — Waits for a candle within the breakout candle's range with lower volume
- **Entry** — Price breaks above inside-candle high (LONG) or below inside-candle low (SHORT) with higher volume
- **Position sizing** — Risk-based (0.5% of ₹10L capital per trade)
- **Exit** — Partial at 1:2 RR (50% book), trailing stop, EMA 10 crossover
- **Schedule** — Every 5 min, 9:30 AM – 3:30 PM

### 2. Multi-TF RSI Nifty Option
Nifty index options strategy using multi-timeframe RSI divergence and candlestick patterns:

- **Candles** — 5-min and 15-min built from 1-min Nifty index ticks
- **RSI (Wilder's smoothing)** — 14-period RSI on both timeframes, compared against 20-period SMA
- **Entry** — Both 5m and 15m RSI above SMA + bullish candlestick pattern → buy CE; both below SMA + bearish pattern → buy PE
- **Strike selection** — ATM ± 100 range, premium ₹60–100
- **Exit** — RSI crossover (5m RSI crosses SMA), timeframe conflict, or 11:30 AM time square-off
- **Max 1 trade per day**

## Prerequisites

- Java 21
- Gradle 9.x (bundled wrapper)
- Docker (optional, for containerised deployment)
- Telegram bot token (from [@BotFather](https://t.me/BotFather))

## Quick Start

### 1. Clone & Configure

```bash
cp .env.example .env
```

Edit `.env` with your credentials:

```env
TELEGRAM_BOT_TOKEN=your-bot-token
TELEGRAM_CHAT_ID=your-chat-id
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
│   ├── NseConfig.java                   # NSE API URL builder
│   ├── TelegramConfig.java              # Telegram API WebClient
│   ├── StartupHealthCheck.java          # Startup connectivity checks
│   ├── KiteConfig.java                  # Kite API credentials
│   ├── WebClientConfig.java             # Shared WebClient configuration
│   ├── WebMvcConfig.java               # CORS configuration
│   └── LogbackHttpAppender.java         # HTTP log forwarding
├── controller/
│   ├── AuthController.java              # Kite Connect OAuth endpoints
│   ├── OptionChainController.java       # Option chain query endpoints
│   └── PositionController.java          # Position query endpoints
├── dto/
│   ├── OptionChainData.java             # NSE API response DTO
│   ├── IndexQuote.java                  # Index OHLC quote
│   ├── StockQuote.java                  # Equity quote DTO
│   ├── VandeBharatSignal.java           # Vande Bharat signal DTO
│   ├── MultiTfRSINiftySignal.java        # Multi-TF RSI signal DTO
│   ├── OhlcCandle.java                  # OHLC candle record
│   ├── Position.java                    # Position DTO
│   └── ... (Kite auth, order DTOs)
├── scheduler/
│   ├── VandeBharatStrategyScheduler.java  # 5-min fixed rate + cron reset/scan
│   └── MultiTfRSINiftyScheduler.java      # 1-min fixed rate + cron reset
├── exception/
│   ├── GlobalExceptionHandler.java      # REST error handling
│   ├── KiteApiException.java            # Kite API errors
│   └── KiteAuthenticationException.java # Auth errors
└── service/
    ├── NseOptionChainClient.java        # NSE API client (option chain, quotes, pre-open, bhavcopy)
    ├── BseOptionChainClient.java         # BSE option chain client
    ├── VandeBharatStrategyService.java   # Vande Bharat breakout strategy
    ├── MultiTfRSINiftyOptionService.java # Multi-TF RSI Nifty option strategy
    ├── CandlestickPatternService.java    # Candlestick pattern detection
    ├── TelegramServiceImpl.java          # Telegram messaging
    ├── TelegramService.java              # Telegram interface
    ├── KiteAuthService.java              # Kite Connect OAuth
    ├── ZerodhaApiClientImpl.java         # Kite trade API
    └── ZerodhaPositionService.java       # Position management
```

```
NSE API ──► NseOptionChainClient ──► VandeBharatStrategyService ──► TelegramService
         (every 5 min)                  │
                                         ├─ PDH/PDL breakout detection
                                         ├─ Inside candle filter
                                         ├─ Entry signal generation
                                         └─ Trailing stop / EMA 10 exit

NSE API ──► NseOptionChainClient ──► MultiTfRSINiftyOptionService ──► TelegramService
         (every 1 min)                   │
                                         ├─ 5m/15m RSI (Wilder's) vs SMA
                                         ├─ Candlestick pattern detection
                                         ├─ Entry signal (CE/PE)
                                         └─ RSI crossover exit
```

## Telegram Messages

| Trigger | Content |
|---------|---------|
| **Startup** | Application initialized — NSE & Telegram OK |
| **Vande Bharat Pre-Market** | Top 10 F&O stocks by %CHNG, PDH/PDL, advances/declines |
| **Vande Bharat Signal** | Stock, direction, entry, SL, PDH/PDL, suggested qty |
| **Vande Bharat Partial Exit** | Stock, target hit, 50% booked at 1:2 RR |
| **Vande Bharat Exit** | Stock, exit price, reason (TRAILING STOP / EMA 10) |
| **Multi-TF RSI Signal** | Direction, option type, strike, premium, spot, patterns, RSI alignment |
| **Multi-TF RSI Exit** | Direction, exit premium, reason (RSI CROSS / CONFLICT) |
| **Error** | Scheduler error message |

## Scheduler Schedule

| Time (IST) | Action |
|------------|--------|
| 8:00 AM | Multi-TF RSI daily reset |
| 9:00 AM | Vande Bharat daily reset |
| 9:10 AM | Vande Bharat pre-market scan (top 10 F&O stocks) |
| 9:15 AM – 3:30 PM | Vande Bharat analysis every 5 min |
| 9:30 AM – 11:30 AM | Multi-TF RSI Nifty evaluation every 1 min |
| Weekends | No activity |

## API Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| GET | `/api/v1/auth/login-url` | Kite Connect login URL |
| GET | `/api/v1/auth/callback` | OAuth redirect handler |
| POST | `/api/v1/auth/session` | Exchange `request_token` for session |
| GET | `/api/v1/positions` | All active positions |
| GET | `/api/v1/positions/nifty/intraday` | NIFTY intraday positions |
| GET | `/actuator/health` | Health check |

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TELEGRAM_BOT_TOKEN` | Yes | — | Telegram bot token (from @BotFather) |
| `TELEGRAM_CHAT_ID` | Yes | — | Target chat/group ID |
| `KITE_API_KEY` | No | — | Zerodha API key |
| `KITE_API_SECRET` | No | — | Zerodha API secret |
| `NSE_HOME_URL` | No | `https://www.nseindia.com/option-chain` | NSE homepage (for cookie) |
| `KITE_BASE_URL` | No | `https://api.kite.trade` | Kite REST API base |
| `KITE_LOGIN_URL` | No | `https://kite.zerodha.com/connect/login` | Kite Connect login |
| `APP_BASE_URL` | No | `https://localhost:443` | Server base URL |
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
# Tag for your registry
docker tag sharadprsn/kite-trading:latest sharadprsn/kite-trading:1.0.0

# Push to registry
docker push sharadprsn/kite-trading:latest
docker push sharadprsn/kite-trading:1.0.0

# Run from registry (after push)
docker run -d --name kite-trading -p 443:443 --env-file .env sharadprsn/kite-trading:latest
```

> **Note**: Replace `your-registry` with your actual Docker registry (e.g., `docker.io/username`, `ghcr.io/username`, or a private registry). Login first with `docker login` if required.

### Tests

| Test file | Tests | Covers |
|-----------|-------|--------|
| `VandeBharatStrategyServiceTest` | — | Vande Bharat breakout, inside candle, entry/exit signals |
| `MultiTfRSINiftyOptionServiceTest` | — | RSI computation, entry/exit logic, strike selection |
| `CandlestickPatternServiceTest` | — | Pattern detection (hammer, engulfing, marubozu, doji) |
| `StartupHealthCheckTest` | 4 | NSE/Telegram success and failure paths |
| `NseConnectivityTest` | 3 | NSE config URLs, option chain data validation |
| `BseConnectivityTest` | 1 | BSE configuration and SENSEX option chain fetch validation |
| `KiteTradingApplicationTests` | 1 | Context load |

## Project Conventions

- Java 21, Spring Boot 3.4.x, Gradle 9.x
- Constructor injection, immutable DTOs (Java records)
- JUnit 5 + Mockito for tests (≥80% coverage target)
- Structured logging via SLF4J
- No `var` keyword, no heavy dependencies without explicit need

## Vande Bharat Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `EMA_PERIOD` | 10 | EMA period for trailing exit |
| `MAX_CANDLES` | 20 | Max 5-min candles kept in memory |
| `MAX_ACTIVE_STOCKS` | 10 | Max stocks tracked simultaneously |
| `CAPITAL` | ₹10,00,000 | Position sizing base |
| `RISK_PER_TRADE_PCT` | 0.5% | Risk per trade |
| `INSIDE_CANDLE_LIMIT` | 6 | Max candles to wait for inside candle |
| `MAX_CONCURRENT_TRADES` | 2 | Max simultaneous trades |
| `PARTIAL_EXIT_MULTIPLIER` | 2 | 1:2 RR for partial exit |
| `PARTIAL_EXIT_PCT` | 50% | Fraction to book at partial exit |

## Multi-TF RSI Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `RSI_PERIOD` | 14 | RSI computation period (Wilder's smoothing) |
| `SMA_PERIOD` | 20 | SMA period for RSI signal line |
| `MIN_PREMIUM` | ₹60 | Minimum option premium for strike selection |
| `MAX_PREMIUM` | ₹100 | Maximum option premium for strike selection |
| `MAX_TRADES_PER_DAY` | 1 | Max trades per day |
| `FIVE_MIN_CANDLES` | 5 | 1-min ticks per 5-min candle |
| `FIFTEEN_MIN_CANDLES` | 3 | 5-min candles per 15-min candle |
