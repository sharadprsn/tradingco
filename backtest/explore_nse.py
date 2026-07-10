"""
Explore nse_index and security_wise_archive
"""
from nsepython import nse_index, security_wise_archive, nsefetch
import pandas as pd

# Explore nse_index
print("=== nse_index() ===")
idx = nse_index()
print(f"Type: {type(idx).__name__}")
print(f"Shape: {idx.shape}")
print(f"Columns: {list(idx.columns)}")
print(idx.head(10).to_string())
print()
print(idx.tail(5).to_string())

# Try to get option chain data via nsefetch (which handles cookies)
print("\n=== Option chain via nsefetch ===")
try:
    url = "https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY"
    oc = nsefetch(url)
    if oc:
        print(f"Type: {type(oc).__name__}")
        if isinstance(oc, dict):
            print(f"Keys: {list(oc.keys())[:5]}")
            if "records" in oc and oc["records"]:
                rec = oc["records"]
                print(f"Underlying: {rec.get('underlyingValue')}")
                data = rec.get("data", [])
                print(f"Strikes: {len(data)}")
                if data:
                    # Aggregate OI changes
                    pe_chg = sum(s.get("PE", {}).get("changeinOpenInterest", 0) or 0 for s in data)
                    ce_chg = sum(s.get("CE", {}).get("changeinOpenInterest", 0) or 0 for s in data)
                    print(f"PE OI Change: {pe_chg}")
                    print(f"CE OI Change: {ce_chg}")
    else:
        print("None received")
except Exception as e:
    print(f"Error: {e}")

# Try nsefetch with different URL patterns
print("\n=== nsefetch with F&O bhavcopy ===")
try:
    url = "https://www.nseindia.com/api/historical/fo/03072026"
    fo = nsefetch(url)
    if fo:
        print(f"Got FO data: {type(fo).__name__}")
        print(str(fo)[:500])
    else:
        print("None")
except Exception as e:
    print(f"Error: {e}")


# Check if security_wise_archive works with proper params
print("\n=== security_wise_archive ===")
try:
    import inspect
    sig = inspect.signature(security_wise_archive)
    print(f"Signature: {sig}")
    src = inspect.getsource(security_wise_archive)
    print(f"Source:\n{src[:500]}")
except Exception as e:
    print(f"Error inspecting: {e}")
