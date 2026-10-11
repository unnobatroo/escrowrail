"""End-to-end HTTP tests: UBO rejection, GitHub PR release path,
DHL delivery path, asymmetric cancellation."""

import uuid
from decimal import Decimal

import httpx
import pytest
import respx
from fastapi.testclient import TestClient
from httpx import Response
from sqlmodel import Session

from app.exceptions import UnsupportedCurrencyPairError
from app.services.fx_service import FXService
from tests.conftest import StubFXProvider

PR_URL = "https://github.com/acme/robotics/pull/42"
TRACKING_KEY = "DHL-TRACK-999"


def _setup_entities(client: TestClient) -> tuple[str, str, str, str]:
    buyer = client.post(
        "/entities",
        json={
            "legal_name": "Acme Hardware US",
            "ubo_tax_id": "TAX-US-1001",
            "country": "US",
        },
    ).json()
    seller = client.post(
        "/entities",
        json={
            "legal_name": "Berlin Robotics GmbH",
            "ubo_tax_id": "TAX-DE-2002",
            "country": "DE",
        },
    ).json()
    buyer_usd = client.post(
        "/accounts",
        json={
            "entity_id": buyer["id"],
            "currency": "USD",
            "initial_balance": "50000",
        },
    ).json()
    seller_eur = client.post(
        "/accounts",
        json={"entity_id": seller["id"], "currency": "EUR"},
    ).json()
    return buyer["id"], seller["id"], buyer_usd["id"], seller_eur["id"]


def _create_contract(
    client: TestClient,
    buyer_id: str,
    seller_id: str,
    tracking_type: str = "GITHUB_PR",
    tracking_key: str = PR_URL,
) -> str:
    resp = client.post(
        "/contracts",
        json={
            "buyer_id": buyer_id,
            "seller_id": seller_id,
            "target_currency": "EUR",
            "target_amount": "10000",
            "source_currency": "USD",
            "tracking_type": tracking_type,
            "tracking_key": tracking_key,
        },
    )
    assert resp.status_code == 201, resp.text
    return str(resp.json()["id"])


def _fund(client: TestClient, contract_id: str, account_id: str) -> None:
    resp = client.post(
        f"/contracts/{contract_id}/fund",
        json={"account_id": account_id, "deposit_amount": "11000"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "FUNDED"


def test_ubo_collision_returns_403(client: TestClient) -> None:
    buyer_id, seller_id, _, _ = _setup_entities(client)
    subsidiary = client.post(
        "/entities",
        json={
            "legal_name": "Acme Subsidiary DE",
            "ubo_tax_id": "TAX-US-1001",  # same UBO as buyer
            "country": "DE",
        },
    ).json()
    resp = client.post(
        "/contracts",
        json={
            "buyer_id": buyer_id,
            "seller_id": subsidiary["id"],
            "target_currency": "EUR",
            "target_amount": "1000",
            "source_currency": "USD",
            "tracking_type": "GITHUB_PR",
            "tracking_key": "x",
        },
    )
    assert resp.status_code == 403
    body = resp.json()
    assert body["error_code"] == "ERR_CIRCULAR_OWNERSHIP"
    assert body["code"] == "CIRCULAR_OWNERSHIP_DETECTED"
    assert seller_id != subsidiary["id"]


def test_happy_path_github_pr_merge_settles(client: TestClient, session: Session) -> None:
    buyer_id, seller_id, buyer_usd, seller_eur = _setup_entities(client)
    contract_id = _create_contract(client, buyer_id, seller_id)
    _fund(client, contract_id, buyer_usd)

    resp = client.post(
        "/webhooks/github",
        json={
            "action": "closed",
            "pull_request": {"merged": True, "html_url": PR_URL},
        },
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "settled"
    assert resp.json()["contract_id"] == contract_id

    contract = client.get(f"/contracts/{contract_id}").json()
    assert contract["status"] == "SETTLED"

    seller = client.get(f"/accounts/{seller_eur}").json()
    assert Decimal(str(seller["balance"])) == Decimal("10053.19")

    # Every monetary event is recorded in the ledger.
    ledger = client.get(f"/contracts/{contract_id}/ledger").json()
    assert len(ledger) == 5  # fee + deposit + conversion + fee + release
    for entry in ledger:
        assert Decimal(str(entry["amount"])) > 0


def test_unmerged_pr_action_ignored(client: TestClient) -> None:
    buyer_id, seller_id, buyer_usd, _ = _setup_entities(client)
    contract_id = _create_contract(client, buyer_id, seller_id)
    _fund(client, contract_id, buyer_usd)
    resp = client.post(
        "/webhooks/github",
        json={
            "action": "closed",
            "pull_request": {"merged": False, "html_url": PR_URL},
        },
    )
    assert resp.json()["status"] == "ignored"
    assert client.get(f"/contracts/{contract_id}").json()["status"] == "FUNDED"


def test_dhl_delivery_webhook_settles(client: TestClient) -> None:
    buyer_id, seller_id, buyer_usd, _ = _setup_entities(client)
    contract_id = _create_contract(
        client,
        buyer_id,
        seller_id,
        tracking_type="SHIPPING_CARRIER",
        tracking_key=TRACKING_KEY,
    )
    _fund(client, contract_id, buyer_usd)

    resp = client.post(
        "/webhooks/logistics",
        json={
            "carrier": "DHL",
            "status": "DELIVERED",
            "tracking_key": TRACKING_KEY,
        },
    )
    assert resp.json()["status"] == "settled"
    assert client.get(f"/contracts/{contract_id}").json()["status"] == "SETTLED"


def test_dhl_in_transit_ignored(client: TestClient) -> None:
    buyer_id, seller_id, buyer_usd, _ = _setup_entities(client)
    contract_id = _create_contract(
        client,
        buyer_id,
        seller_id,
        tracking_type="SHIPPING_CARRIER",
        tracking_key=TRACKING_KEY,
    )
    _fund(client, contract_id, buyer_usd)
    resp = client.post(
        "/webhooks/logistics",
        json={
            "carrier": "DHL",
            "status": "IN_TRANSIT",
            "tracking_key": TRACKING_KEY,
        },
    )
    assert resp.json()["status"] == "ignored"
    assert client.get(f"/contracts/{contract_id}").json()["status"] == "FUNDED"


def test_wrong_carrier_rejected(client: TestClient) -> None:
    resp = client.post(
        "/webhooks/logistics",
        json={
            "carrier": "FEDEX",
            "status": "DELIVERED",
            "tracking_key": "x",
        },
    )
    assert resp.status_code == 400
    assert resp.json()["error_code"] == "ERR_WEBHOOK_VALIDATION"


def test_asymmetric_cancellation_flow(
    client: TestClient, session: Session, fx: StubFXProvider
) -> None:
    buyer_id, seller_id, buyer_usd, _ = _setup_entities(client)
    contract_id = _create_contract(client, buyer_id, seller_id)
    _fund(client, contract_id, buyer_usd)

    # EUR weakens to 0.90 USD before cancellation.
    fx.usd_values["EUR"] = Decimal("0.90")
    resp = client.post(f"/contracts/{contract_id}/cancel")
    assert resp.status_code == 200
    assert resp.json()["status"] == "CANCELLED"

    buyer = client.get(f"/accounts/{buyer_usd}").json()
    # 50000 - 11000 deposit + (10134.26 EUR * 0.90) refund
    assert Decimal(str(buyer["balance"])) == Decimal("48120.83")


def test_contract_not_found_404(client: TestClient) -> None:
    resp = client.get(f"/contracts/{uuid.uuid4()}")
    assert resp.status_code == 404
    assert resp.json()["error_code"] == "ERR_NOT_FOUND"


def test_fx_service_uses_frankfurter_then_falls_back() -> None:
    with respx.mock:
        respx.get("https://api.frankfurter.dev/v1/latest").mock(
            return_value=Response(200, json={"rates": {"USD": 1.10}})
        )
        live = FXService(live_enabled=True)
        assert live.get_rate("EUR", "USD") == Decimal("1.10")

        respx.get("https://api.frankfurter.dev/v1/latest").mock(
            side_effect=httpx.ConnectError("offline")
        )
        offline = FXService(live_enabled=True)
        assert offline.get_rate("EUR", "USD") == Decimal("1.08")


def test_fx_same_currency_rate_is_one() -> None:
    assert FXService().get_rate("EUR", "EUR") == Decimal("1")


def test_fx_unsupported_pair_raises() -> None:
    with pytest.raises(UnsupportedCurrencyPairError) as exc:
        FXService().get_rate("EUR", "JPY")
    assert exc.value.error_code == "ERR_UNSUPPORTED_CURRENCY_PAIR"


def test_health(client: TestClient) -> None:
    assert client.get("/health").json() == {"status": "ok"}


def test_health_probes(client: TestClient) -> None:
    assert client.get("/health/live").json() == {"status": "alive"}
    ready = client.get("/health/ready").json()
    assert ready["status"] == "ready"
    assert ready["ledger_balanced"] is True
    assert ready["discrepancy_cents"] == 0


def test_metrics_endpoint(client: TestClient, session: Session) -> None:
    buyer_id, seller_id, buyer_usd, _ = _setup_entities(client)
    contract_id = _create_contract(client, buyer_id, seller_id)
    _fund(client, contract_id, buyer_usd)
    client.post(
        "/webhooks/github",
        json={
            "action": "closed",
            "pull_request": {"merged": True, "html_url": PR_URL},
        },
    )
    body = client.get("/metrics").text
    assert "escrow_contracts_created_total" in body
    assert "escrow_contracts_settled_total" in body
    assert "ledger_invariant_discrepancy_cents 0" in body
    assert "http_request_duration_seconds" in body


def test_idempotent_webhook_replay(client: TestClient, session: Session) -> None:
    buyer_id, seller_id, buyer_usd, _ = _setup_entities(client)
    contract_id = _create_contract(client, buyer_id, seller_id)
    _fund(client, contract_id, buyer_usd)
    payload = {
        "action": "closed",
        "pull_request": {"merged": True, "html_url": PR_URL},
    }
    r1 = client.post("/webhooks/github", json=payload)
    r2 = client.post("/webhooks/github", json=payload)
    assert r1.status_code == 200 and r1.json()["status"] == "settled"
    assert r2.status_code == 200
    assert r2.headers.get("x-idempotent-replay") == "1"
    assert r2.json() == r1.json()
