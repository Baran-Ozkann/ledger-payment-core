"""Creates the accounts the load scenarios spend, through the API rather than through SQL.

Seeding with INSERT statements would be faster and would also be a lie: the balances would not
have come from ledger entries, so I2 and I3 would be false before the first measured request and
the reconciliation numbers taken during a run would mean nothing. Every kurus in here is funded
from an EQUITY account through the same endpoint a caller would use.

Writes load/accounts.json, which the k6 scenarios read.
"""

import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

DEFAULT_URL = "http://127.0.0.1:8080"
DEFAULT_ACCOUNTS = 10_000
# Enough that no account can run dry over a full ramp: the scenarios move 1 000 kurus at a time,
# and no single account is picked anywhere near a million times in eleven minutes.
OPENING_BALANCE = 1_000_000_000
CLIENT_ID = "load-seed"


def post(base_url: str, path: str, body: dict) -> dict:
    request = urllib.request.Request(
        f"{base_url}{path}",
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Client-Id": CLIENT_ID,
            "Idempotency-Key": str(uuid.uuid4()),
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read())


def create_account(base_url: str, account_type: str, owner_ref: str) -> str:
    return post(base_url, "/v1/accounts", {"account_type": account_type, "owner_ref": owner_ref})["id"]


def fund(base_url: str, equity: str, account: str, amount: int) -> None:
    post(base_url, "/v1/funding", {
        "from_account": equity,
        "to_account": account,
        "amount": amount,
        "description": "load seed",
    })


def seed(base_url: str, count: int, workers: int) -> dict:
    equity = create_account(base_url, "EQUITY", "load-equity")
    revenue = create_account(base_url, "REVENUE", "load-fees")
    print(f"equity  {equity}\nrevenue {revenue}", flush=True)

    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        accounts = list(pool.map(
            lambda index: create_account(base_url, "LIABILITY", f"load-{index}"), range(count)))
    print(f"{len(accounts)} accounts in {time.monotonic() - started:.1f}s", flush=True)

    # Every funding transfer debits the one EQUITY row, so this half is itself a hot-account
    # workload and will not go faster than that row allows. It is not measured; it only has to
    # finish, and its slowness is the same finding scenario H reports later.
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(lambda account: fund(base_url, equity, account, OPENING_BALANCE), accounts))
    print(f"funded in {time.monotonic() - started:.1f}s", flush=True)

    return {"equity": equity, "revenue": revenue, "accounts": accounts}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--accounts", type=int, default=DEFAULT_ACCOUNTS)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--out", default=str(pathlib.Path(__file__).parent / "accounts.json"))
    args = parser.parse_args()

    try:
        seeded = seed(args.url, args.accounts, args.workers)
    except urllib.error.URLError as unreachable:
        print(f"the ledger at {args.url} did not answer: {unreachable}", file=sys.stderr)
        return 1

    pathlib.Path(args.out).write_text(json.dumps(seeded, indent=2), encoding="utf-8")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
