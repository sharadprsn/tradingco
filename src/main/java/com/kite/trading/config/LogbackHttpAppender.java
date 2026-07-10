package com.kite.trading.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.status.ErrorStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Custom Logback appender that forwards log events as JSON to a remote HTTP endpoint.
 *
 * <p><b>Configuration in {@code logback-spring.xml}:</b>
 *
 * <pre>{@code
 * <appender name="HTTP" class="com.kite.trading.config.LogbackHttpAppender">
 *     <url>${LOG_URL}</url>
 * </appender>
 * }</pre>
 *
 * <p>The appender builds a lightweight JSON payload without any additional dependencies. It is
 * designed to run under an {@link ch.qos.logback.classic.AsyncAppender AsyncAppender} so that
 * network I/O does not block the application thread.
 *
 * <p>If the {@code url} is empty or {@code null} the appender is a no-op, making it safe to keep in
 * the configuration even when no endpoint is set.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
public class LogbackHttpAppender extends AppenderBase<ILoggingEvent> {

  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

  private static final int CONNECT_TIMEOUT_MS = 2_000;
  private static final int READ_TIMEOUT_MS = 5_000;

  private String url;
  private Layout<ILoggingEvent> layout;
  private Encoder<ILoggingEvent> encoder;

  /**
   * Sets the target URL for log forwarding. When empty or {@code null} {@link
   * #append(ILoggingEvent)} becomes a no-op.
   *
   * @param url the HTTP(S) endpoint URL
   */
  public void setUrl(final String url) {
    this.url = url;
  }

  /**
   * Injects an optional Logback {@link Layout} for custom message formatting.
   *
   * @param layout the layout to use for the logged message
   */
  public void setLayout(final Layout<ILoggingEvent> layout) {
    this.layout = layout;
  }

  /**
   * Injects an optional Logback {@link Encoder} for binary output.
   *
   * @param encoder the encoder to use
   */
  public void setEncoder(final Encoder<ILoggingEvent> encoder) {
    this.encoder = encoder;
  }

  /** {@inheritDoc} */
  @Override
  protected void append(final ILoggingEvent event) {
    if (url == null || url.isBlank()) {
      return;
    }

    final String jsonPayload = buildJsonPayload(event);
    if (jsonPayload == null) {
      return;
    }

    final byte[] body = jsonPayload.getBytes(StandardCharsets.UTF_8);

    try {
      final URL endpoint = URI.create(url).toURL();
      final HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();

      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setDoOutput(true);

      try (final OutputStream os = conn.getOutputStream()) {
        os.write(body);
        os.flush();
      }

      final int responseCode = conn.getResponseCode();
      if (responseCode < 200 || responseCode >= 300) {
        addStatus(
            new ErrorStatus(
                "HTTP log endpoint returned " + responseCode + " for URL: " + url, this));
      }

      conn.disconnect();
    } catch (final IOException e) {
      addStatus(
          new ErrorStatus(
              "Failed to send log event to URL: " + url + " - " + e.getMessage(), this));
    }
  }

  /**
   * Builds a lightweight JSON payload from a Logback event.
   *
   * @param event the logging event to serialise
   * @return a JSON string representing the event
   */
  private String buildJsonPayload(final ILoggingEvent event) {
    final StringBuilder json = new StringBuilder(512);
    json.append('{');

    appendString(
        json, "timestamp", TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())));
    json.append(',');
    appendString(json, "level", event.getLevel().toString());
    json.append(',');
    appendString(json, "logger", event.getLoggerName());
    json.append(',');
    appendString(json, "thread", event.getThreadName());

    json.append(',');

    final String formattedMessage;
    if (layout != null) {
      formattedMessage = layout.doLayout(event);
    } else if (encoder != null) {
      formattedMessage = new String(encoder.encode(event), StandardCharsets.UTF_8);
    } else {
      formattedMessage = event.getFormattedMessage();
    }
    appendString(json, "message", formattedMessage);

    final IThrowableProxy throwable = event.getThrowableProxy();
    if (throwable != null) {
      json.append(',');
      appendString(json, "exception", buildStackTrace(throwable));
    }

    json.append('}');
    return json.toString();
  }

  /** Builds a compact stack-trace string from a Logback throwable proxy. */
  private static String buildStackTrace(final IThrowableProxy throwable) {
    final StringBuilder sb = new StringBuilder(512);
    sb.append(throwable.getClassName()).append(": ").append(throwable.getMessage());

    for (final StackTraceElementProxy step : throwable.getStackTraceElementProxyArray()) {
      sb.append("\n\tat ").append(step.toString());
    }

    IThrowableProxy cause = throwable.getCause();
    while (cause != null) {
      sb.append("\nCaused by: ")
          .append(cause.getClassName())
          .append(": ")
          .append(cause.getMessage());
      for (final StackTraceElementProxy step : cause.getStackTraceElementProxyArray()) {
        sb.append("\n\tat ").append(step.toString());
      }
      cause = cause.getCause();
    }

    return sb.toString();
  }

  /** Appends a JSON string key-value pair, properly escaping special characters. */
  private static void appendString(final StringBuilder json, final String key, final String value) {
    json.append('"');
    json.append(key);
    json.append("\":\"");
    if (value != null) {
      for (int i = 0; i < value.length(); i++) {
        final char ch = value.charAt(i);
        switch (ch) {
          case '"' -> json.append("\\\"");
          case '\\' -> json.append("\\\\");
          case '\n' -> json.append("\\n");
          case '\r' -> json.append("\\r");
          case '\t' -> json.append("\\t");
          default -> json.append(ch);
        }
      }
    }
    json.append('"');
  }
}
