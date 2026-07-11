package com.kite.trading.ml;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

final class SentimentAnalyzer {

  private static final Logger logger = LoggerFactory.getLogger(SentimentAnalyzer.class);

  private static final List<String> RSS_FEEDS =
      List.of(
          "https://news.google.com/rss/search?q=Nifty+Sensex+stock+market&hl=en-IN&gl=IN&ceid=IN:en",
          "https://news.google.com/rss/search?q=Indian+stock+market+today&hl=en-IN&gl=IN&ceid=IN:en",
          "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms");

  private static final Set<String> POSITIVE_WORDS =
      Set.of(
          "surge",
          "rally",
          "gain",
          "bullish",
          "uptick",
          "positive",
          "growth",
          "rise",
          "rising",
          "strong",
          "recovery",
          "boom",
          "profit",
          "upgrade",
          "outperform",
          "breakout",
          "momentum",
          "optimistic",
          "outlook",
          "expansion",
          "accelerate",
          "rebound",
          "upside",
          "soar",
          "record",
          "high",
          "green",
          "bull",
          "higher",
          "gains",
          "up",
          "jump",
          "climb",
          "advance",
          "strength",
          "improve",
          "improving",
          "upbeat");

  private static final Set<String> NEGATIVE_WORDS =
      Set.of(
          "fall",
          "plunge",
          "crash",
          "bearish",
          "downtick",
          "negative",
          "decline",
          "slump",
          "weak",
          "loss",
          "downgrade",
          "underperform",
          "selloff",
          "sell-off",
          "volatile",
          "pessimistic",
          "slowdown",
          "contraction",
          "decay",
          "drop",
          "lower",
          "red",
          "bear",
          "falling",
          "falls",
          "down",
          "fear",
          "panic",
          "correction",
          "warning",
          "risk",
          "stress",
          "crisis",
          "bleak",
          "gloomy",
          "uncertainty",
          "turmoil",
          "retreat",
          "dip",
          "slide",
          "tumble",
          "shed",
          "drag",
          "worsen",
          "weaken",
          "deteriorate");

  private static final Pattern WORD_BOUNDARY = Pattern.compile("[^a-zA-Z]+");

  private final HttpClient httpClient;
  private final ReentrantLock cacheLock = new ReentrantLock();

  private volatile double cachedScore;
  private volatile String cachedLabel;
  private volatile long cacheTimestamp;

  private static final long CACHE_TTL_MS = 300_000;

  SentimentAnalyzer() {
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  record SentimentResult(double score, String label) {}

  SentimentResult getSentiment() {
    long now = System.currentTimeMillis();
    if (now - cacheTimestamp < CACHE_TTL_MS) {
      return new SentimentResult(cachedScore, cachedLabel);
    }

    cacheLock.lock();
    try {
      if (now - cacheTimestamp < CACHE_TTL_MS) {
        return new SentimentResult(cachedScore, cachedLabel);
      }

      List<String> headlines = fetchHeadlines();
      if (headlines.isEmpty()) {
        cachedScore = 0.0;
        cachedLabel = "neutral";
        cacheTimestamp = now;
        return new SentimentResult(0.0, "neutral");
      }

      double totalScore = 0.0;
      for (String h : headlines) {
        totalScore += scoreHeadline(h);
      }
      double avgScore = totalScore / headlines.size();

      String label;
      if (avgScore > 0.15) {
        label = "bullish";
      } else if (avgScore < -0.15) {
        label = "bearish";
      } else {
        label = "neutral";
      }

      cachedScore = avgScore;
      cachedLabel = label;
      cacheTimestamp = now;

      logger.info("Sentiment: score={}, label={}, headlines={}", avgScore, label, headlines.size());
      return new SentimentResult(avgScore, label);
    } finally {
      cacheLock.unlock();
    }
  }

  private List<String> fetchHeadlines() {
    List<String> allHeadlines = new ArrayList<>();
    for (String feedUrl : RSS_FEEDS) {
      try {
        HttpRequest req =
            HttpRequest.newBuilder()
                .uri(URI.create(feedUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
          allHeadlines.addAll(parseRss(resp.body()));
        }
      } catch (Exception e) {
        logger.debug("RSS feed error for {}: {}", feedUrl, e.getMessage());
      }
    }
    return allHeadlines.size() > 20 ? allHeadlines.subList(0, 20) : allHeadlines;
  }

  private List<String> parseRss(String xml) {
    List<String> headlines = new ArrayList<>();
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
      NodeList items = doc.getElementsByTagName("item");
      for (int i = 0; i < items.getLength(); i++) {
        Element item = (Element) items.item(i);
        String title = getTextContent(item, "title");
        if (title != null && !title.isBlank()) {
          headlines.add(title.strip());
        }
      }
    } catch (Exception e) {
      logger.debug("RSS parse error: {}", e.getMessage());
    }
    return headlines;
  }

  private static String getTextContent(Element parent, String tag) {
    NodeList list = parent.getElementsByTagName(tag);
    if (list.getLength() > 0) {
      return list.item(0).getTextContent();
    }
    return null;
  }

  private static double scoreHeadline(String headline) {
    String lower = headline.toLowerCase();
    String[] words = WORD_BOUNDARY.split(lower);
    int positiveCount = 0;
    int negativeCount = 0;
    for (String w : words) {
      if (POSITIVE_WORDS.contains(w)) {
        positiveCount++;
      } else if (NEGATIVE_WORDS.contains(w)) {
        negativeCount++;
      }
    }
    int total = positiveCount + negativeCount;
    if (total == 0) {
      return 0.0;
    }
    return (double) (positiveCount - negativeCount) / total;
  }
}
