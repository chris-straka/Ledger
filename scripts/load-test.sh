#!/usr/bin/env bash
# Load test: N postings at concurrency C against a running Ledger API.
#
#   ./scripts/load-test.sh [N] [C] [MODE]     (defaults: 2000 16 spread)
#
# MODE=spread  every posting moves 1 unit between two of 32 accounts
#              (low contention: SERIALIZABLE rarely aborts)
# MODE=hot     every posting debits the same account (one hot row: the
#              SERIALIZABLE retry loop does the work)
#
# Reports throughput, client-side latency percentiles, the status mix, and
# the server's own retry counter. Each run creates fresh accounts, so it can
# be repeated against the same database. Point LEDGER_API at the server
# (default http://127.0.0.1:18680, the scripts/run-local.sh port).
set -euo pipefail

N="${1:-2000}"
C="${2:-16}"
MODE="${3:-spread}"
API="${LEDGER_API:-http://127.0.0.1:18680}"

echo "[load] N=$N concurrency=$C mode=$MODE api=$API machine=$(uname -m)"
LOAD_N="$N" LOAD_C="$C" LOAD_MODE="$MODE" LOAD_API="$API" python3 - <<'PY'
import json, os, random, statistics, threading, time, urllib.request, uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

api, n, c, mode = os.environ["LOAD_API"], int(os.environ["LOAD_N"]), int(os.environ["LOAD_C"]), os.environ["LOAD_MODE"]
run = uuid.uuid4().hex[:8]

def call(method, path, body=None, key=None):
    req = urllib.request.Request(api + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **({"Idempotency-Key": key} if key else {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}

def account(i, typ):
    s, b = call("POST", "/v1/accounts", {"code": f"L{run}-{i}", "name": f"load {i}",
        "currency": "USD", "type": typ, "overdraftPolicy": "ALLOW"})
    assert s == 201, s
    return b["accountId"]

def retries():
    try:
        with urllib.request.urlopen(api + "/actuator/prometheus", timeout=5) as r:
            text = r.read().decode()
    except Exception:
        return None
    total = 0.0
    for line in text.splitlines():
        if line.startswith("ledger_posting_retries "):
            total += float(line.rsplit(" ", 1)[1])
    return total

accts = [account(i, "ASSET") for i in range(32 if mode == "spread" else 2)]
rng = random.Random(42)
pairs = []
for i in range(n):
    if mode == "hot":
        pairs.append((accts[0], accts[1]))
    else:
        a, b = rng.sample(accts, 2)
        pairs.append((a, b))

lat, status = [], Counter()
lock = threading.Lock()
def post(i):
    d, cr = pairs[i]
    body = {"description": f"load {i}", "effectiveAt": "2026-10-05T12:00:00Z", "entries": [
        {"accountId": d, "side": "DEBIT", "amountMinorUnits": "1"},
        {"accountId": cr, "side": "CREDIT", "amountMinorUnits": "1"}]}
    t0 = time.perf_counter()
    s, _ = call("POST", "/v1/postings", body, key=f"load-{run}-{i}")
    dt = time.perf_counter() - t0
    with lock:
        lat.append(dt); status[s] += 1

r0 = retries()
start = time.perf_counter()
with ThreadPoolExecutor(max_workers=c) as ex:
    list(ex.map(post, range(n)))
wall = time.perf_counter() - start
r1 = retries()

lat.sort()
q = lambda p: lat[min(len(lat) - 1, int(p * len(lat)))] * 1000
print(f"[load] {n} postings in {wall:.2f}s -> {n / wall:.0f} postings/s")
print(f"[load] latency ms: p50={q(0.50):.1f} p95={q(0.95):.1f} p99={q(0.99):.1f} max={lat[-1]*1000:.1f}")
print(f"[load] status: {dict(sorted(status.items()))}")
if r0 is not None and r1 is not None:
    print(f"[load] server-side serialization retries during run: {r1 - r0:.0f}")
bal = sum(int(call("GET", f"/v1/accounts/{a}/balance")[1]["balanceMinorUnits"]) for a in accts)
print(f"[load] sum of load-account balances (asset accounts, must be 0): {bal}")
assert bal == 0, "conservation violated"
PY
