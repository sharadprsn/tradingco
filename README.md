# Kite Trading API

A Spring Boot application for interacting with Zerodha's Kite API to manage trading positions, including NIFTY intraday positions.

## Features

- **Authentication**: OAuth-based login via Kite Connect URL
- **Position Management**: Fetch all active trading positions
- **NIFTY Intraday**: Filter and retrieve NIFTY intraday positions specifically
- **RESTful API**: Well-structured REST endpoints following best practices
- **Docker Support**: Containerized deployment ready

## Prerequisites

- Java 21 or higher
- Gradle 8.x
- Docker (for containerized deployment)
- Zerodha Kite API credentials (API Key only)

## Project Structure

```
kite-java/
├── src/
│   ├── main/
│   │   ├── java/com/kite/trading/
│   │   │   ├── KiteTradingApplication.java
│   │   │   ├── config/
│   │   │   │   ├── KiteConfig.java
│   │   │   │   └── WebClientConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java
│   │   │   │   └── PositionController.java
│   │   │   ├── dto/
│   │   │   │   ├── ErrorResponse.java
│   │   │   │   ├── KiteSession.java
│   │   │   │   ├── LoginUrlResponse.java
│   │   │   │   ├── Position.java
│   │   │   │   ├── PositionsResponse.java
│   │   │   │   └── SessionRequest.java
│   │   │   ├── exception/
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   ├── KiteApiException.java
│   │   │   │   └── KiteAuthenticationException.java
│   │   │   └── service/
│   │   │       ├── KiteAuthService.java
│   │   │       ├── PositionService.java
│   │   │       ├── ZerodhaApiClient.java
│   │   │       ├── ZerodhaApiClientImpl.java
│   │   │       ├── ZerodhaAuthService.java
│   │   │       └── ZerodhaPositionService.java
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/com/kite/trading/
├── build.gradle
├── settings.gradle
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── .gitignore
└── README.md
```

## Architecture

This application follows SOLID principles:

- **Single Responsibility Principle**: Each class has a single responsibility
- **Open/Closed Principle**: Code is open for extension but closed for modification
- **Liskov Substitution Principle**: Interfaces are used to define contracts
- **Interface Segregation Principle**: Multiple specific interfaces instead of one general-purpose interface
- **Dependency Inversion Principle**: High-level modules don't depend on low-level modules; both depend on abstractions

### Design Patterns

- **Repository Pattern**: API clients abstract the data access layer
- **Strategy Pattern**: Different position filtering strategies
- **DTO Pattern**: Data transfer objects for API communication
- **Service Layer Pattern**: Business logic encapsulated in services

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/kite-java.git
cd kite-java
```

### 2. Set Up Environment Variables

Copy the `.env.example` file to `.env` and fill in your credentials:

```bash
cp .env.example .env
```

Edit `.env` with your Zerodha Kite credentials:
```env
KITE_API_KEY=your-actual-api-key
KITE_API_SECRET=your-actual-api-secret
```

The `.env` file is automatically loaded at startup by the `spring-dotenv` library.
You can also set the same values as OS environment variables — Spring Boot will
pick those up natively.

### 3. Build the Application

```bash
./gradlew build
```

### 4. Run the Application

```bash
./gradlew bootRun
```

Or run the JAR directly:

```bash
java -jar build/libs/kite-trading-1.0.0.jar
```

The application will start on port 8080.

## API Endpoints

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/auth/login-url` | Get the Kite Connect login URL (open in browser) |
| GET | `/api/v1/auth/callback` | OAuth redirect handler — exchanges `request_token` for session |
| POST | `/api/v1/auth/session` | Exchange `request_token` for API session (manual) |
| POST | `/api/v1/auth/logout` | Logout and invalidate session |
| GET | `/api/v1/auth/status` | Check authentication status |

### Positions

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/positions` | Get all active positions |
| GET | `/api/v1/positions/nifty/intraday` | Get NIFTY intraday positions |
| GET | `/api/v1/positions/net` | Get net positions |
| GET | `/api/v1/positions/day` | Get day trading positions |

### Health Check

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/actuator/health` | Application health status |

## Usage Examples

### Step 1: Get Login URL

```bash
curl http://localhost:8080/api/v1/auth/login-url
```

Response:
```json
{
  "login_url": "https://kite.zerodha.com/connect/login?api_key=your-api-key&v3=1"
}
```

Open the returned URL in a browser, log in with your Zerodha credentials and TOTP. After successful login, the browser redirects to your redirect URL with a `request_token` query parameter.

### Step 2: Generate Session

```bash
curl -X POST http://localhost:8080/api/v1/auth/session \
  -H "Content-Type: application/json" \
  -d '{
    "request_token": "your-request-token-from-redirect"
  }'
```

### Get All Positions

```bash
curl http://localhost:8080/api/v1/positions
```

### Get NIFTY Intraday Positions

```bash
curl http://localhost:8080/api/v1/positions/nifty/intraday
```

### Check Authentication Status

```bash
curl http://localhost:8080/api/v1/auth/status
```

### Logout

```bash
curl -X POST http://localhost:8080/api/v1/auth/logout
```

## Running with Docker

### Build and Run with Docker Compose

1. Create your `.env` file with credentials
2. Run the following commands:

```bash
# Build and start the container
docker-compose up --build

# Run in detached mode
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the container
docker-compose down
```

### Build Docker Image Manually

```bash
# Build the image
docker build -t kite-trading:latest .

# Run the container
docker run -d \
  --name kite-trading \
  -p 8080:8080 \
  -e KITE_API_KEY=your-api-key \
  -e KITE_API_SECRET=your-api-secret \
  kite-trading:latest
```

### Check Container Status

```bash
# List running containers
docker ps

# View container logs
docker logs kite-trading

# Check health status
docker inspect --format='{{.State.Health.Status}}' kite-trading
```

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `KITE_API_KEY` | Yes | - | Your Zerodha API key |
| `KITE_API_SECRET` | Yes | - | Your Zerodha API secret (used for checksum) |
| `KITE_BASE_URL` | No | `https://api.kite.trade` | Kite REST API base URL |
| `KITE_LOGIN_URL` | No | `https://kite.zerodha.com/connect/login` | Kite Connect login URL |
| `KITE_REDIRECT_URL` | No | `http://localhost:8080/api/v1/auth/callback` | OAuth redirect URL |
| `LOG_HTTP_URL` | No | *(empty)* | Remote HTTP endpoint for log forwarding (JSON POST) |
| `LOG_HTTP_LEVEL` | No | `WARN` | Minimum level for forwarded logs (e.g. `WARN`, `ERROR`) |

### Application Properties

Key application properties in `application.properties`:

```properties
# Server port
server.port=8080

# Actuator endpoints
management.endpoints.web.exposure.include=health,info

# Logging levels
logging.level.com.kite.trading=INFO

# HTTP log forwarding (optional)
logging.http.url=https://logs.example.com/ingest
logging.http.level=WARN
```

### HTTP Log Forwarding

Logs at `WARN` level and above can be forwarded as JSON to a remote HTTP endpoint.
This is useful for centralised log aggregation.

| Property | Environment Variable | Description |
|----------|---------------------|-------------|
| `logging.http.url` | `LOG_HTTP_URL` | Target URL for JSON log POSTs |
| `logging.http.level` | `LOG_HTTP_LEVEL` | Minimum log level to forward |

The appender runs on a background thread (via Logback's `AsyncAppender`) so network
latency never blocks the application. When `logging.http.url` is empty the forwarder
is completely disabled.

## Development

### Building for Development

```bash
# Clean build
./gradlew clean build

# Run tests
./gradlew test

# Run with debug logging
./gradlew bootRun --args='--logging.level.com.kite.trading=DEBUG'
```

### Code Quality

The application follows these standards:

- Java 21 features
- SOLID principles
- Constructor dependency injection
- Immutable DTOs (Java records)
- Comprehensive Javadoc documentation
- Structured logging with SLF4J

## Error Handling

The application provides consistent error responses:

```json
{
  "error": "AUTHENTICATION_ERROR",
  "message": "Login failed: Invalid credentials",
  "timestamp": "2024-01-15T10:30:00"
}
```

### Error Types

| Error Type | HTTP Status | Description |
|------------|-------------|-------------|
| `AUTHENTICATION_ERROR` | 401 | Login or token issues |
| `API_ERROR` | 502 | Zerodha API communication errors |
| `INTERNAL_ERROR` | 500 | Unexpected server errors |

## Testing

### Unit Tests

```bash
./gradlew test
```

### Integration Tests

```bash
./gradlew integrationTest
```

### Test Coverage

Generate test coverage report:

```bash
./gradlew jacocoTestReport
```

## Security Considerations

- Never commit `.env` files or credentials to version control
- Use environment variables for sensitive configuration
- The application uses HTTPS for all external API calls
- Session tokens are stored in memory only
- Docker container runs as non-root user

## Performance

- WebClient used for non-blocking HTTP requests
- Connection pooling configured for optimal performance
- Response caching can be added for frequently accessed data

## Troubleshooting

### Common Issues

1. **Authentication Failed**
   - Verify your API credentials
   - Ensure TOTP is synchronized
   - Check if your account is active

2. **Connection Timeout**
   - Check network connectivity
   - Verify Zerodha API status
   - Increase timeout in WebClient config

3. **Docker Build Fails**
   - Ensure Docker daemon is running
   - Check available disk space
   - Verify Dockerfile syntax

### Logs

View application logs:

```bash
# Docker logs
docker logs -f kite-trading

# Local logs
tail -f logs/kite-trading.log
```

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Zerodha Kite API](https://kite.trade/connect/documentation) for the trading API
- [Spring Boot](https://spring.io/projects/spring-boot) for the framework
- [Project Reactor](https://projectreactor.io/) for reactive programming support

## Support

For support, email your-email@example.com or create an issue in the repository.
#   t r a d i n g c o  
 