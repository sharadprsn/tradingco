"""
Directional Option Selling Strategy - Backtest Report
======================================================
Strategy: Sell out-of-the-money options based on OI buildup direction.
  - PE OI buildup > 60% of total = BULLISH -> sell PUT
  - CE OI buildup > 60% of total = BEARISH -> sell CALL  
  - Balanced = NEUTRAL -> short strangle (sell both)

Data: Nifty 50 daily prices (1 year)
Signals: Since NSE F&O historical OI data is blocked by anti-scraping,
         we use price-volume action as directional proxy.
         In production, OI data comes live from NSE option chain API.

Metrics: Direction accuracy, win/loss streaks, monthly breakdown

Author: Generated for kite-java backtesting
"""

import yfinance as yf
import pandas as pd
import numpy as np
from datetime import datetime, timedelta
import warnings
warnings.filterwarnings("ignore")

print("=" * 72)
print("  DIRECTIONAL OPTION SELLING - BACKTEST REPORT")
print("  Generated: " + datetime.now().strftime("%Y-%m-%d %H:%M"))
print("=" * 72)

# -- 1. Fetch Data ----------------------------------------------
print("\n [1/5] Fetching Nifty 50 daily data...")
nifty = yf.download("^NSEI", period="1y", interval="1d")
nifty.columns = ["Close", "High", "Low", "Open", "Volume"]
nifty.index = pd.to_datetime(nifty.index)
print(f"       {len(nifty)} trading days: {nifty.index[0].date()} -> {nifty.index[-1].date()}")

# -- 2. Feature Engineering --------------------------------------
print(" [2/5] Computing features and signals...")
df = nifty.copy()
df["Ret"] = df["Close"].pct_change() * 100
df["Vol_MA20"] = df["Volume"].rolling(20).mean()
df["Vol_Ratio"] = df["Volume"] / df["Vol_MA20"]
df["ATR"] = df[["High", "Low", "Close"]].apply(
    lambda r: r["High"] - r["Low"], axis=1).rolling(14).mean()
df["ATR_Pct"] = df["ATR"] / df["Close"] * 100

# Directional signal based on price + volume (proxy for OI buildup)
def signal_fn(row):
    ret = row["Ret"]
    vr = row["Vol_Ratio"]
    if pd.isna(ret) or pd.isna(vr):
        return "NEUTRAL", 50
    # Strong bullish: up on good volume
    if ret > 0.3 and (vr > 0.8 or ret > 0.5):
        conf = min(abs(ret) * 25 + 50, 92)
        return "BULLISH", round(conf)
    # Strong bearish: down on good volume
    if ret < -0.3 and (vr > 0.8 or ret < -0.5):
        conf = min(abs(ret) * 25 + 50, 92)
        return "BEARISH", round(conf)
    return "NEUTRAL", 50

signals = df.apply(signal_fn, axis=1, result_type="expand")
df["Signal"] = signals[0].values
df["Confidence"] = signals[1].values

# -- 3. Backtest Logic -------------------------------------------
print(" [3/5] Running backtest simulation...")
results = []
for i in range(len(df) - 1):
    row = df.iloc[i]
    next_ret = df.iloc[i + 1]["Ret"]

    sig = row["Signal"]
    conf = row["Confidence"]

    if sig == "BULLISH":
        correct = next_ret > 0
        magnitude = next_ret  # positive Nifty -> put loses value -> profit
        direction_msg = "^"
    elif sig == "BEARISH":
        correct = next_ret < 0
        magnitude = -next_ret  # negative Nifty -> call loses value -> profit
        direction_msg = "v"
    else:
        correct = abs(next_ret) < 0.5
        magnitude = 0.5 - abs(next_ret)
        direction_msg = "->"

    results.append({
        "Date": row.name,
        "Close": round(row["Close"], 2),
        "Signal": sig,
        "Confidence": conf,
        "Next_Ret": round(next_ret, 2) if not pd.isna(next_ret) else 0,
        "Correct": correct,
        "Magnitude": round(magnitude, 2) if not pd.isna(magnitude) else 0,
    })

bt = pd.DataFrame(results)

# -- 4. Performance Metrics --------------------------------------
print(" [4/5] Computing performance metrics...")

total = len(bt)
n_bull = (bt["Signal"] == "BULLISH").sum()
n_bear = (bt["Signal"] == "BEARISH").sum()
n_neut = (bt["Signal"] == "NEUTRAL").sum()

bull_acc = bt[bt["Signal"] == "BULLISH"]["Correct"].mean() * 100
bear_acc = bt[bt["Signal"] == "BEARISH"]["Correct"].mean() * 100
neut_acc = bt[bt["Signal"] == "NEUTRAL"]["Correct"].mean() * 100
overall_acc = bt["Correct"].mean() * 100

SEP = "=" * 72
DASH = "-" * 72
print(f"\n{SEP}")
print(f"  RESULTS SUMMARY")
print(f"{SEP}")
print(f"  Total trading days:        {total}")
print(f"  Backtest period:           {bt['Date'].iloc[0].date()} -> {bt['Date'].iloc[-1].date()}")
print(f"  Signal distribution:")
print(f"    BULLISH (sell PUT):      {n_bull:>4} ({n_bull/total*100:5.1f}%)")
print(f"    BEARISH (sell CALL):     {n_bear:>4} ({n_bear/total*100:5.1f}%)")
print(f"    NEUTRAL (short strangle): {n_neut:>4} ({n_neut/total*100:5.1f}%)")
print(f"{DASH}")
print(f"  DIRECTION ACCURACY (next-day prediction):")
print(f"    BULLISH -> market up:    {bull_acc:6.1f}%")
print(f"    BEARISH -> market down:  {bear_acc:6.1f}%")
print(f"    NEUTRAL -> range-bound:  {neut_acc:6.1f}%")
print(f"    {DASH}")
print(f"    OVERALL ACCURACY:       {overall_acc:6.1f}%")
print(f"{DASH}")

# Confidence filtering
print(f"\n  CONFIDENCE FILTERING:")
for thresh in [50, 60, 70, 80]:
    sub = bt[bt["Confidence"] >= thresh]
    if len(sub) > 0:
        print(f"    Confidence >= {thresh}:  {len(sub):>4} trades, accuracy = {sub['Correct'].mean()*100:5.1f}%")

# Monthly breakdown
print(f"\n  MONTHLY BREAKDOWN:")
bt["Month"] = bt["Date"].dt.to_period("M")
monthly = bt.groupby("Month").agg(
    Trades=("Signal", "count"),
    Bullish=("Signal", lambda x: (x == "BULLISH").sum()),
    Bearish=("Signal", lambda x: (x == "BEARISH").sum()),
    Accuracy=("Correct", "mean"),
    Avg_Next_Ret=("Next_Ret", "mean"),
).round(3)
monthly["Accuracy"] = (monthly["Accuracy"] * 100).round(1)
monthly["Avg_Next_Ret"] = monthly["Avg_Next_Ret"].round(2)
print(monthly.to_string())

# Streak analysis
print(f"\n  STREAK ANALYSIS:")
bt["SGroup"] = (bt["Correct"] != bt["Correct"].shift()).cumsum()
streaks = bt.groupby(["SGroup", "Correct"]).agg(
    Length=("Date", "count"),
    Period=("Date", lambda x: f"{x.iloc[0].date()} -> {x.iloc[-1].date()}")
).reset_index()
max_win = streaks[streaks["Correct"] == True]["Length"].max()
max_loss = streaks[streaks["Correct"] == False]["Length"].max()
print(f"    Longest win streak:  {max_win} days")
print(f"    Longest loss streak: {max_loss} days")

# Avg move by signal
print(f"\n  AVERAGE NEXT-DAY MOVE BY SIGNAL:")
for sig in ["BULLISH", "BEARISH", "NEUTRAL"]:
    sub = bt[bt["Signal"] == sig]
    if len(sub) > 0:
        avg_r = sub["Next_Ret"].mean()
        std_r = sub["Next_Ret"].std()
        print(f"    {sig:8s}: avg = {avg_r:+.2f}%, std = {std_r:.2f}%")

# -- 5. Trade Log (last 20) -------------------------------------
print(f"\n [5/5] Sample trade log (last 20):")
sample = bt.tail(20)[["Date", "Close", "Signal", "Confidence", "Next_Ret", "Correct"]]
sample["Date"] = sample["Date"].dt.date
sample["Correct"] = sample["Correct"].map({True: "OK", False: "NO"})
print(sample.to_string(index=False))

print(f"\n{'='*72}")
print(f"  NOTES")
print(f"{'='*72}")
print(f"  1. OI data source: NSE F&O historical APIs are blocked by")
print(f"     anti-scraping. Signals use price-volume proxy.")
print(f"  2. For live OI data, deploy with Zerodha/Upstox broker API.")
print(f"  3. Run 'collect_oi.py' daily to build your own OI history.")
print(f"  4. Accuracy measures next-day direction prediction only.")
print(f"     Actual option P&L depends on strike selection, entry/exit.")
print(f"{'='*72}")
print(f"  BACKTEST COMPLETE")
print(f"{'='*72}")
