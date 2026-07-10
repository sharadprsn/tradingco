from nsepython import nse_optionchain_scrapper, nsefetch, nse_index
from datetime import datetime, timedelta
import yfinance as yf
import pandas as pd

# Try the NSE option chain API for live data
print("Trying NSE option chain...")
try:
    oc = nse_optionchain_scrapper("NIFTY")
    if oc:
        print(f"Type: {type(oc)}")
        if isinstance(oc, dict):
            keys = list(oc.keys())[:10]
            print(f"Keys: {keys}")
            if "records" in oc and oc["records"]:
                underlying = oc["records"].get("underlyingValue")
                print(f"Underlying: {underlying}")
                data = oc["records"].get("data", [])
                print(f"Strikes: {len(data)}")
                if data:
                    sample = data[0]
                    print(f"Sample keys: {list(sample.keys())}")
                    
                    # Extract OI data for PE and CE separately
                    pe_total_oi = 0
                    ce_total_oi = 0
                    pe_total_oi_change = 0
                    ce_total_oi_change = 0
                    for strike in data:
                        if "PE" in strike and strike["PE"]:
                            pe_total_oi += strike["PE"].get("openInterest", 0) or 0
                            pe_total_oi_change += strike["PE"].get("changeinOpenInterest", 0) or 0
                        if "CE" in strike and strike["CE"]:
                            ce_total_oi += strike["CE"].get("openInterest", 0) or 0
                            ce_total_oi_change += strike["CE"].get("changeinOpenInterest", 0) or 0
                    
                    print(f"\nPE Total OI: {pe_total_oi}")
                    print(f"CE Total OI: {ce_total_oi}")
                    print(f"PE OI Change: {pe_total_oi_change}")
                    print(f"CE OI Change: {ce_total_oi_change}")
                    if pe_total_oi > 0 and ce_total_oi > 0:
                        pcr = pe_total_oi / ce_total_oi
                        print(f"PCR (OI): {pcr:.4f}")
                    
                    if (pe_total_oi_change + ce_total_oi_change) > 0:
                        pe_pct = pe_total_oi_change / (pe_total_oi_change + ce_total_oi_change) * 100
                        print(f"PE OI Change %: {pe_pct:.1f}%")
except Exception as e:
    print(f"Error: {type(e).__name__}: {e}")

# Get Nifty index info
print("\nTrying nse_index for NIFTY 50...")
try:
    idx = nse_index("NIFTY 50")
    if idx is not None:
        if isinstance(idx, pd.DataFrame):
            print(f"Shape: {idx.shape}")
            print(f"Columns: {list(idx.columns)}")
            print(idx.tail(5))
        elif isinstance(idx, dict):
            print(f"Keys: {list(idx.keys())[:10]}")
except Exception as e:
    print(f"Error: {e}")

# Fetch Nifty daily data from yfinance
print("\nFetching Nifty via yfinance...")
try:
    nifty = yf.download("^NSEI", period="6mo", interval="1d")
    print(f"Nifty shape: {nifty.shape}")
    print(nifty.tail(3))
except Exception as e:
    print(f"Error: {e}")
