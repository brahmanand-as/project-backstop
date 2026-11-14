"""Post deterministic synthetic events through the ingestion API."""
import argparse
import base64
import concurrent.futures
import datetime as dt
import json
import os
import random
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=["load", "seed", "showcase"])
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--run", default="demo")
args = parser.parse_args()
password = os.environ["DEMO_PASSWORD"]
start = dt.datetime(2026, 1, 1, tzinfo=dt.timezone.utc)
rng = random.Random(2026)

def request(user, endpoint, body=None):
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()
    req = urllib.request.Request(args.base + endpoint,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization":"Basic " + auth, "Content-Type":"application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)

def events():
    merchant_count, event_count = {"seed": (200, 500), "load": (200, 50), "showcase": (6, 100)}[args.mode]
    for index in range(merchant_count):
        merchant = f"{args.run}-m{index:03d}"
        for n in range(event_count):
            offset = ((n * 7 * 86400) // event_count) if args.mode == "seed" else n
            kind = "SALE"
            if index < 5 and n >= event_count * (.80 if args.mode == "showcase" else .94):
                kind = "CHARGEBACK" if index % 2 else "REFUND"
            yield {"eventId":f"{args.run}-{index}-{n}","merchantId":merchant,"type":kind,
                   "amount":rng.randint(50,500),"currency":"INR",
                   "occurredAt":(start + dt.timedelta(seconds=offset)).isoformat()}

def send(event):
    begin = time.perf_counter()
    result = request("ingest","/api/merchant-events",event)
    if result["duplicate"]:
        raise RuntimeError("Use a new --run value for fresh events")
    return (time.perf_counter() - begin) * 1000

if args.mode == "load":
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        samples = sorted(pool.map(send,events()))
    for quantile in (.5,.95,.99):
        print(f"p{int(quantile*100)}: {samples[int(quantile*len(samples))-1]:.2f} ms")
else:
    for event in events():
        send(event)
    flagged = []
    page = 0
    while True:
        result = request("analyst1",f"/api/merchants?flagged=true&page={page}&size=100")
        flagged += [m["merchantId"] for m in result["items"] if m["merchantId"].startswith(args.run+"-m")]
        page += 1
        if page*100 >= result["total"]:
            break
    expected = {f"{args.run}-m{i:03d}" for i in range(5)}
    print("Flagged:", sorted(flagged))
    assert expected.issubset(flagged) and len(set(flagged)-expected) <= 2
