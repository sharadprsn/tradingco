"""
Debug NSE option chain - get full content
"""
from nsepython import nse_optionchain_scrapper, option_chain
import json

# Try nse_optionchain_scrapper
print("=== nse_optionchain_scrapper ===")
try:
    r = nse_optionchain_scrapper("NIFTY")
    if r:
        print(f"Type: {type(r).__name__}, keys: {list(r.keys())[:10]}")
        if "records" in r and r["records"]:
            rec = r["records"]
            print(f"  Underlying: {rec.get('underlyingValue')}")
            data = rec.get("data", [])
            print(f"  Strikes count: {len(data)}")
            if data:
                print(f"  Sample keys: {list(data[0].keys())}")
                
                # Aggregate OI
                pe_total_chg = 0
                ce_total_chg = 0
                for strike in data:
                    pe = strike.get("PE")
                    ce = strike.get("CE")
                    if pe and pe.get("changeinOpenInterest"):
                        pe_total_chg += pe["changeinOpenInterest"]
                    if ce and ce.get("changeinOpenInterest"):
                        ce_total_chg += ce["changeinOpenInterest"]
                
                print(f"\n  PE OI Change: {pe_total_chg}")
                print(f"  CE OI Change: {ce_total_chg}")
                total_chg = abs(pe_total_chg) + abs(ce_total_chg)
                if total_chg > 0:
                    pe_pct = pe_total_chg / total_chg * 100
                    print(f"  PE OI Change %: {pe_pct:.1f}%")
                    if pe_pct > 60:
                        print(f"  >>> BULLISH")
                    elif pe_pct < 40:
                        print(f"  >>> BEARISH")
                    else:
                        print(f"  >>> NEUTRAL")
        else:
            print("  No 'records' key or records is None")
            print(f"  Full keys: {list(r.keys())}")
            print(f"  Sample: {json.dumps(r, indent=2)[:1000]}")
    else:
        print("  Result is None")
except Exception as e:
    import traceback
    traceback.print_exc()

# Try option_chain
print("\n=== option_chain ===")
try:
    r = option_chain("NIFTY")
    if r:
        print(f"Type: {type(r).__name__}")
        if isinstance(r, dict):
            print(f"  Keys: {list(r.keys())[:10]}")
            if "records" in r and r["records"]:
                print(f"  Underlying: {r['records'].get('underlyingValue')}")
        elif hasattr(r, "columns"):
            print(f"  Shape: {r.shape}")
            print(f"  Columns: {list(r.columns)}")
    else:
        print("  Result is None")
except Exception as e:
    import traceback
    traceback.print_exc()
