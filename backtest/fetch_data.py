import requests
import io
import zipfile
import csv
from datetime import datetime, timedelta

session = requests.Session()
session.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
})

# First visit NSE homepage to get cookies
r1 = session.get("https://www.nseindia.com", timeout=15)
print(f"Homepage: {r1.status_code}, cookies: {dict(session.cookies)}")

# Try to download F&O bhavcopy for recent trading days
for days_ago in [3, 4, 5, 6, 7, 10, 14, 21]:
    d = datetime.now() - timedelta(days=days_ago)
    if d.weekday() >= 5:
        continue
    month = d.strftime("%b").upper()
    date_str = d.strftime(f"%d{month}%Y")
    url = f"https://nsearchives.nseindia.com/content/historical/DERIVATIVES/{d.year}/{month}/fo{date_str}bhav.csv.zip"
    r2 = session.get(url, timeout=15, headers={"Referer": "https://www.nseindia.com/"})
    if r2.status_code == 200 and len(r2.content) > 5000:
        print(f"SUCCESS: {url} (size={len(r2.content)})")
        try:
            with zipfile.ZipFile(io.BytesIO(r2.content)) as zf:
                names = zf.namelist()
                print(f"Files: {names}")
                with zf.open(names[0]) as csvf:
                    content = csvf.read().decode("utf-8")
                    lines = content.splitlines()
                    print(f"Columns: {lines[0]}")
                    print(f"Rows: {len(lines) - 1}")
                    # Save for later use
                    with open(f"backtest/fo_{date_str}.csv", "w", encoding="utf-8") as fout:
                        fout.write(content)
        except Exception as e:
            print(f"Extract error: {e}")
        break
    else:
        print(f"FAIL: {url} (status={r2.status_code}, size={len(r2.content)})")
        if r2.status_code == 200:
            print(f"Content: {r2.text[:200]}")
