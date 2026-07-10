"""
Debug NSE option chain API access
"""
from nsepython import nsefetch, nse_optionchain_ltp, nse_optionchain_scrapper, option_chain
import json

# Try multiple option chain functions
print("1. Trying nse_optionchain_ltp...")
try:
    r = nse_optionchain_ltp("NIFTY")
    print(f"   Result: {type(r).__name__}")
    if r:
        print(f"   Preview: {str(r)[:200]}")
except Exception as e:
    print(f"   Error: {e}")

print("\n2. Trying nse_optionchain_scrapper...")
try:
    r = nse_optionchain_scrapper("NIFTY")
    print(f"   Result: {type(r).__name__}")
    if r:
        if isinstance(r, dict):
            print(f"   Keys: {list(r.keys())[:5]}")
        else:
            print(f"   Preview: {str(r)[:200]}")
except Exception as e:
    print(f"   Error: {e}")

print("\n3. Trying option_chain...")
try:
    r = option_chain("NIFTY")
    print(f"   Result: {type(r).__name__}")
    if r:
        if isinstance(r, dict):
            print(f"   Keys: {list(r.keys())[:5]}")
        elif hasattr(r, 'columns'):
            print(f"   Shape: {r.shape}")
            print(f"   Columns: {list(r.columns)}")
except Exception as e:
    print(f"   Error: {e}")

print("\n4. Trying direct nsefetch...")
try:
    url = "/api/option-chain-indices?symbol=NIFTY"
    r = nsefetch(url)
    print(f"   Result: {type(r).__name__}")
    if r:
        if isinstance(r, dict):
            print(f"   Keys: {list(r.keys())[:5]}")
except Exception as e:
    print(f"   Error: {e}")

print("\n5. Checking nsepython library source...")
import inspect
print(inspect.getsource(nsefetch))
