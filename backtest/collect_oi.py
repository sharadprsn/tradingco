"""
NSE OI Data Collector
Run this script daily after 3:30 PM to collect end-of-day OI data.
Builds a historical OI database for proper backtesting.
"""

import os
import json
import csv
import time
from datetime import datetime, timedelta
from nsepython import nse_optionchain_scrapper

DATA_DIR = os.path.join(os.path.dirname(__file__), "oi_history")
os.makedirs(DATA_DIR, exist_ok=True)

def collect_oi_snapshot():
    """Fetch NIFTY option chain and save OI data."""
    print(f"[{datetime.now().strftime('%H:%M:%S')}] Fetching NIFTY option chain...")
    
    oc = nse_optionchain_scrapper("NIFTY")
    if not oc or "records" not in oc:
        print("  Failed to fetch option chain (NSE blocking)")
        return False
    
    rec = oc["records"]
    underlying = rec.get("underlyingValue")
    data = rec.get("data", [])
    
    timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M")
    
    # Aggregate OI data
    pe_total_oi = 0
    ce_total_oi = 0
    pe_total_chg = 0
    ce_total_chg = 0
    strikes_data = []
    
    for strike in data:
        strike_price = strike.get("strikePrice")
        pe = strike.get("PE") or {}
        ce = strike.get("CE") or {}
        
        pe_oi = pe.get("openInterest", 0) or 0
        ce_oi = ce.get("openInterest", 0) or 0
        pe_chg = pe.get("changeinOpenInterest", 0) or 0
        ce_chg = ce.get("changeinOpenInterest", 0) or 0
        
        pe_total_oi += pe_oi
        ce_total_oi += ce_oi
        pe_total_chg += pe_chg
        ce_total_chg += ce_chg
        
        strikes_data.append({
            "strike": strike_price,
            "pe_oi": pe_oi,
            "pe_oi_chg": pe_chg,
            "ce_oi": ce_oi,
            "ce_oi_chg": ce_chg,
        })
    
    # Summary
    total_chg = abs(pe_total_chg) + abs(ce_total_chg)
    pe_pct = pe_total_chg / total_chg * 100 if total_chg > 0 else 50
    pcr = pe_total_oi / ce_total_oi if ce_total_oi > 0 else 0
    
    if pe_pct > 60:
        direction = "BULLISH"
    elif pe_pct < 40:
        direction = "BEARISH"
    else:
        direction = "NEUTRAL"
    
    record = {
        "timestamp": timestamp,
        "underlying": underlying,
        "pcr": round(pcr, 4),
        "pe_oi": pe_total_oi,
        "ce_oi": ce_total_oi,
        "pe_oi_chg": pe_total_chg,
        "ce_oi_chg": ce_total_chg,
        "pe_pct": round(pe_pct, 1),
        "direction": direction,
        "strikes": strikes_data,
    }
    
    # Save individual snapshot
    fname = f"oi_{timestamp}.json"
    with open(os.path.join(DATA_DIR, fname), "w") as f:
        json.dump(record, f, indent=2)
    print(f"  Saved {fname}")
    
    # Append to CSV summary
    csv_file = os.path.join(DATA_DIR, "oi_history.csv")
    is_new = not os.path.exists(csv_file)
    with open(csv_file, "a", newline="") as f:
        writer = csv.writer(f)
        if is_new:
            writer.writerow(["timestamp", "underlying", "pcr", "pe_oi", "ce_oi",
                            "pe_oi_chg", "ce_oi_chg", "pe_pct", "direction"])
        writer.writerow([timestamp, underlying, round(pcr, 4), pe_total_oi, ce_total_oi,
                        pe_total_chg, ce_total_chg, round(pe_pct, 1), direction])
    
    print(f"  Summary: under={underlying}, PCR={pcr:.2f}, PE%={pe_pct:.1f}% -> {direction}")
    return True

# === MAIN ===
if __name__ == "__main__":
    print("=" * 60)
    print("NSE OI Data Collector")
    print("=" * 60)
    
    # Try up to 3 times with delays (NSE anti-scraping is intermittent)
    for attempt in range(3):
        if collect_oi_snapshot():
            break
        if attempt < 2:
            wait = 10 * (attempt + 1)
            print(f"  Retrying in {wait}s...")
            time.sleep(wait)
    else:
        print("  Failed after 3 attempts. Try again later.")
