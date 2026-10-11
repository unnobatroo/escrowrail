#!/usr/bin/env python3
"""Standalone smoke test against a running Escrowrail server.

Exercises the complete lifecycle end to end: health probes, entity/account
setup, UBO collusion rejection, contract creation + funding, spot
conversion, webhook settlement, idempotent webhook replay, and final
balance assertions. stdlib only (urllib) so it runs anywhere.

Usage: BASE_URL=http://localhost:8000 python scripts/smoke_test.py
"""

import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from collections.abc import Mapping
from decimal import Decimal
from typing import Any

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8000").rstrip("/")
TRACKING_KEY = f"https://github.com/smoke/repo/pull/{uuid.uuid4().hex[:8]}"

FAILED = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global FAILED
    mark = "PASS" if condition else "FAIL"
    if not condition:
        FAILED += 1
    print(f"[{mark}] {name}{' — ' + detail if detail else ''}")


def request(
    method: str, path: str, body: Mapping[str, Any] | None = None
) -> tuple[int, dict[str, Any], dict[str, str]]:
    req = urllib.request.Request(
        BASE_URL + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read() or b"{}"), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read() or b"{}")
        except json.JSONDecodeError:
            payload = {}
        return exc.code, payload, dict(exc.headers)


def main() -> int:
    # --- health probes ---
    status, body, _ = request("GET", "/health/live")
    check("GET /health/live", status == 200 and body.get("status") == "alive")

    status, body, _ = request("GET", "/health/ready")
    check(
        "GET /health/ready",
        status == 200 and body.get("ledger_balanced") is True,
        f"discrepancy={body.get('discrepancy_cents')}",
    )

    req = urllib.request.Request(BASE_URL + "/metrics")
    with urllib.request.urlopen(req, timeout=15) as resp:
        metrics_text = resp.read().decode()
    check(
        "GET /metrics exposes escrow counters",
        "escrow_contracts_created_total" in metrics_text
        and "http_request_duration_seconds" in metrics_text,
    )

    # --- entities ---
    _, buyer, _ = request(
        "POST",
        "/entities",
        {"legal_name": "Smoke Buyer", "ubo_tax_id": "SMOKE-B", "country": "US"},
    )
    _, seller, _ = request(
        "POST",
        "/entities",
        {"legal_name": "Smoke Seller", "ubo_tax_id": "SMOKE-S", "country": "DE"},
    )
    check("entities created", "id" in buyer and "id" in seller)

    # --- UBO collusion rejection ---
    _, twin, _ = request(
        "POST",
        "/entities",
        {"legal_name": "Smoke Twin", "ubo_tax_id": "SMOKE-B", "country": "US"},
    )
    status, body, _ = request(
        "POST",
        "/contracts",
        {
            "buyer_id": buyer["id"],
            "seller_id": twin["id"],
            "target_currency": "EUR",
            "target_amount": "100",
            "source_currency": "USD",
            "tracking_type": "GITHUB_PR",
            "tracking_key": "https://github.com/smoke/collude/1",
        },
    )
    check(
        "UBO collusion rejected (403 + code)",
        status == 403 and body.get("code") == "CIRCULAR_OWNERSHIP_DETECTED",
        f"status={status} body={body}",
    )

    # --- accounts ---
    _, buyer_acc, _ = request(
        "POST",
        "/accounts",
        {"entity_id": buyer["id"], "currency": "USD", "initial_balance": "12000"},
    )
    _, seller_acc, _ = request(
        "POST",
        "/accounts",
        {"entity_id": seller["id"], "currency": "EUR", "initial_balance": "0"},
    )
    check(
        "buyer USD account funded",
        Decimal(str(buyer_acc["balance"])) == Decimal("12000"),
    )

    # --- contract create + fund ---
    status, contract, _ = request(
        "POST",
        "/contracts",
        {
            "buyer_id": buyer["id"],
            "seller_id": seller["id"],
            "target_currency": "EUR",
            "target_amount": "5000",
            "source_currency": "USD",
            "tracking_type": "GITHUB_PR",
            "tracking_key": TRACKING_KEY,
        },
    )
    check("contract created", status == 201 and contract.get("status") == "INITIATED")
    cid = contract["id"]

    status, funded, _ = request(
        "POST",
        f"/contracts/{cid}/fund",
        {"account_id": buyer_acc["id"], "deposit_amount": "6000"},
    )
    check(
        "contract funded with spot conversion",
        status == 200
        and funded.get("status") == "FUNDED"
        and funded.get("escrowed_amount") is not None
        and Decimal(str(funded["escrowed_amount"])) >= Decimal("5000"),
        f"escrowed={funded.get('escrowed_amount')} rate={funded.get('locked_spot_rate')}",
    )

    # --- webhook settlement ---
    payload = {
        "action": "closed",
        "pull_request": {"merged": True, "html_url": TRACKING_KEY},
    }
    status, settled, _ = request("POST", "/webhooks/github", payload)
    check(
        "github merge webhook settles contract",
        status == 200 and settled.get("status") == "settled",
        f"{settled}",
    )

    # duplicate delivery must replay, not re-execute
    status, _, headers = request("POST", "/webhooks/github", payload)
    replayed = any(k.lower() == "x-idempotent-replay" for k in headers)
    check(
        "duplicate webhook replays cached response",
        status == 200 and replayed,
    )

    # --- final balances ---
    _, seller_final, _ = request("GET", f"/accounts/{seller_acc['id']}")
    escrowed = Decimal(str(funded["escrowed_amount"]))
    expected_seller = (escrowed * Decimal("0.992")).quantize(Decimal("0.01"))
    check(
        "seller credited 99.2% of escrowed amount",
        Decimal(str(seller_final["balance"])) == expected_seller,
        f"balance={seller_final['balance']} expected={expected_seller}",
    )

    _, ledger, _ = request("GET", f"/contracts/{cid}/ledger")
    check(
        "contract ledger legs recorded",
        isinstance(ledger, list) and len(ledger) >= 3,
        f"{len(ledger)} legs",
    )

    # --- readiness still clean after the run ---
    status, body, _ = request("GET", "/health/ready")
    check("ledger still balanced after lifecycle", body.get("ledger_balanced") is True)

    print(f"\n{'SMOKE TEST PASSED' if FAILED == 0 else f'{FAILED} CHECK(S) FAILED'}")
    return 0 if FAILED == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
