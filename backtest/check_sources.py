import requests
import re

session = requests.Session()
session.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"})

# Check tradingtick.in for CSV download
r = session.get("https://tradingtick.in/nifty/download-nifty-option-chain-historical-data.php", timeout=15)
print(f"tradingtick: {r.status_code}, len={len(r.content)}")

# Look for download links
links = re.findall(r'href=["\']([^"\']*csv[^"\']*)["\']', r.text, re.I)
print(f"CSV links found: {len(links)}")
for link in links[:5]:
    print(f"  {link}")

# Also look for any .zip or .xlsx
all_links = re.findall(r'href=["\']([^"\']*(?:csv|zip|xlsx|xls)[^"\']*)["\']', r.text, re.I)
print(f"\nData links found: {len(all_links)}")
for link in all_links[:10]:
    print(f"  {link}")

# Look for download buttons
downloads = re.findall(r'href=["\']([^"\']*download[^"\']*)["\']', r.text, re.I)
print(f"\nDownload links: {len(downloads)}")
for dl in downloads[:5]:
    print(f"  {dl}")
