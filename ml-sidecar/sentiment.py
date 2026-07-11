"""Market sentiment analysis using VADER on financial news RSS feeds."""

import logging
import re
import time
from typing import Any

import feedparser
import numpy as np
from nltk.sentiment.vader import SentimentIntensityAnalyzer

logger = logging.getLogger(__name__)

RSS_FEEDS = [
    "https://news.google.com/rss/search?q=Nifty+Sensex+stock+market&hl=en-IN&gl=IN&ceid=IN:en",
    "https://news.google.com/rss/search?q=Indian+stock+market+today&hl=en-IN&gl=IN&ceid=IN:en",
    "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
]

_analyzer: SentimentIntensityAnalyzer | None = None
_cache: dict[str, Any] = {"score": 0.0, "label": "neutral", "headlines": [], "timestamp": 0.0}
_CACHE_TTL_SECONDS = 300


def _load_analyzer() -> None:
    global _analyzer
    if _analyzer is None:
        logger.info("Loading VADER sentiment analyzer")
        _analyzer = SentimentIntensityAnalyzer()
        logger.info("VADER analyzer loaded")


def _fetch_headlines() -> list[str]:
    headlines: list[str] = []
    seen: set[str] = set()

    for url in RSS_FEEDS:
        try:
            feed = feedparser.parse(url)
            for entry in feed.entries:
                title = entry.get("title", "").strip()
                if title and title not in seen:
                    cleaned = re.sub(r"\s+", " ", title)
                    seen.add(cleaned)
                    headlines.append(cleaned)
        except Exception as e:
            logger.debug("RSS feed error for %s: %s", url, e)

    if not headlines:
        logger.warning("No headlines fetched from any RSS feed")
        return []

    logger.info("Fetched %d unique headlines", len(headlines))
    return headlines[:20]


def _analyze(headlines: list[str]) -> tuple[float, str]:
    if not headlines:
        return 0.0, "neutral"

    _load_analyzer()

    try:
        scores = [_analyzer.polarity_scores(h)["compound"] for h in headlines]
        avg_score = float(np.mean(scores)) if scores else 0.0

        if avg_score > 0.15:
            label = "bullish"
        elif avg_score < -0.15:
            label = "bearish"
        else:
            label = "neutral"

        logger.info(
            "Sentiment: score=%.4f, label=%s (headlines=%d)",
            avg_score, label, len(headlines),
        )
        return avg_score, label
    except Exception as e:
        logger.error("Sentiment analysis failed: %s", e)
        return 0.0, "neutral"


def get_market_sentiment(force_refresh: bool = False) -> dict[str, Any]:
    now = time.time()
    if not force_refresh and (now - _cache["timestamp"]) < _CACHE_TTL_SECONDS:
        return {
            "score": _cache["score"],
            "label": _cache["label"],
            "headlines": _cache["headlines"],
            "cached": True,
        }

    headlines = _fetch_headlines()
    score, label = _analyze(headlines)
    top5 = headlines[:5]

    _cache["score"] = score
    _cache["label"] = label
    _cache["headlines"] = top5
    _cache["timestamp"] = now

    return {"score": score, "label": label, "headlines": top5, "cached": False}
