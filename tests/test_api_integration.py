"""End-to-end HTTP tests: UBO rejection, GitHub PR release path,
DHL delivery path, asymmetric cancellation — all through the
authenticated /api/v1 surface."""

import hashlib
import json
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import httpx
import pytest
import respx
from fastapi.testclient import TestClient
from httpx import Response
from sqlmodel import Session, select
from starlette.responses import JSONResponse

from app.config import settings
from app.exceptions import UnsupportedCurrencyPairError
from app.main import app
from app.middleware.idempotency import CLAIM_LEASE_SECONDS, drain_pending
from app.models.auth import User
from app.models.idempotency import IdempotencyRecord
from app.services.auth_service import AuthService
from app.services.fx_service import FXService
from tests.conftest import StubFXProvider, make_account, make_entity, make_user

PR_URL = "https://github.com/acme/robotics/pull/42"
TRACKING_KEY = "DHL-TRACK-999"
BUYER_EMAIL = "buyer@acme.test"
SELLER_EMAIL = "seller@berlin.test"


def _bearer(user: User) -> dict[str, str]:
    return {"Authorization": f"Bearer {AuthService.issue_token(user)}"}


def _setup(session: Session) -> dict[str, object]:
    """Buyer + seller tenants with users and operating accounts."""
    buyer_e = make_entity(session, "Acme Hardware US", "TAX-US-1001", "US")
    seller_e = make_entity(session, "Berlin Robotics GmbH", "TAX-DE-2002", "DE")
    buyer = make_user(session, buyer_e, BUYER_EMAIL)
    seller = make_user(session, seller_e, SELLER_EMAIL)
    buyer_usd = make_account(session, buyer_e.id, "USD", Decimal("50000"))
    seller_eur = make_account(session, seller_e.id, "EUR", Decimal("0"))
    return {
        "buyer": buyer,
        "seller": seller,
        "buyer_entity": buyer_e,
        "seller_entity": seller_e,
        "buyer_usd": buyer_usd,
        "seller_eur": seller_eur,
    }


def _create_contract(
    client: TestClient,
    buyer: User,
    tracking_type: str = "GITHUB_PR",
    tracking_key: str = PR_URL,
    target_amount: str = "10000",
) -> str:
    resp = client.post(
        "/api/v1/escrow",
        headers=_bearer(buyer),
        json={
            "counterparty_email": SELLER_EMAIL,
            "target_amount": target_amount,
            "tracking_type": tracking_type,
            "tracking_key": tracking_key,
        },
    )
    assert resp.status_code == 201, resp.text
    return str(resp.json()["id"])


def _fund(client: TestClient, contract_id: str, buyer: User) -> None:
    resp = client.post(
        f"/api/v1/escrow/{contract_id}/fund",
        headers=_bearer(buyer),
        json={"deposit_amount": "11000"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "FUNDED"


def test_ubo_collision_returns_403(client: TestClient, session: Session) -> None:
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    subsidiary_e = make_entity(session, "Acme Subsidiary DE", "TAX-US-1001", "DE")
    make_user(session, subsidiary_e, "sub@acme-de.test")
    resp = client.post(
        "/api/v1/escrow",
        headers=_bearer(buyer),
        json={
            "counterparty_email": "sub@acme-de.test",
            "target_amount": "1000",
            "tracking_type": "GITHUB_PR",
            "tracking_key": "x",
        },
    )
    assert resp.status_code == 403
    body = resp.json()
    assert body["error_code"] == "ERR_CIRCULAR_OWNERSHIP"
    assert body["code"] == "CIRCULAR_OWNERSHIP_DETECTED"


def test_happy_path_github_pr_merge_settles(client: TestClient, session: Session) -> None:
    env = _setup(session)
    buyer = env["buyer"]
    seller = env["seller"]
    assert isinstance(buyer, User) and isinstance(seller, User)
    contract_id = _create_contract(client, buyer)
    _fund(client, contract_id, buyer)

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

    contract = client.get(f"/api/v1/escrow/{contract_id}", headers=_bearer(buyer)).json()
    assert contract["status"] == "SETTLED"
    assert contract["role"] == "buyer"

    accounts = client.get("/api/v1/accounts", headers=_bearer(seller)).json()
    seller_eur = next(a for a in accounts if a["currency"] == "EUR")
    assert Decimal(str(seller_eur["balance"])) == Decimal("10053.19")

    # Every monetary event is recorded in the contract audit trail.
    audit = client.get(f"/api/v1/escrow/{contract_id}/audit", headers=_bearer(buyer)).json()
    assert len(audit) == 5  # fee + deposit + conversion + fee + release
    for entry in audit:
        assert Decimal(str(entry["amount"])) > 0


def test_unmerged_pr_action_ignored(client: TestClient, session: Session) -> None:
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    contract_id = _create_contract(client, buyer)
    _fund(client, contract_id, buyer)
    resp = client.post(
        "/webhooks/github",
        json={
            "action": "closed",
            "pull_request": {"merged": False, "html_url": PR_URL},
        },
    )
    assert resp.json()["status"] == "ignored"
    assert (
        client.get(f"/api/v1/escrow/{contract_id}", headers=_bearer(buyer)).json()["status"]
        == "FUNDED"
    )


def test_dhl_delivery_webhook_settles(client: TestClient, session: Session) -> None:
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    contract_id = _create_contract(
        client, buyer, tracking_type="SHIPPING_CARRIER", tracking_key=TRACKING_KEY
    )
    _fund(client, contract_id, buyer)

    resp = client.post(
        "/webhooks/logistics",
        json={
            "carrier": "DHL",
            "status": "DELIVERED",
            "tracking_key": TRACKING_KEY,
        },
    )
    assert resp.json()["status"] == "settled"
    assert (
        client.get(f"/api/v1/escrow/{contract_id}", headers=_bearer(buyer)).json()["status"]
        == "SETTLED"
    )


def test_dhl_in_transit_ignored(client: TestClient, session: Session) -> None:
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    contract_id = _create_contract(
        client, buyer, tracking_type="SHIPPING_CARRIER", tracking_key=TRACKING_KEY
    )
    _fund(client, contract_id, buyer)
    resp = client.post(
        "/webhooks/logistics",
        json={
            "carrier": "DHL",
            "status": "IN_TRANSIT",
            "tracking_key": TRACKING_KEY,
        },
    )
    assert resp.json()["status"] == "ignored"
    assert (
        client.get(f"/api/v1/escrow/{contract_id}", headers=_bearer(buyer)).json()["status"]
        == "FUNDED"
    )


def test_logistics_webhook_requires_secret_when_configured(client: TestClient) -> None:
    """With WISE_LOGISTICS_WEBHOOK_SECRET set, forged carrier callbacks are
    rejected; unset (SQLite dev) the check is skipped."""
    object.__setattr__(settings, "logistics_webhook_secret", "carrier-secret")
    try:
        # Distinct tracking keys per request — identical bodies would hit
        # the idempotency replay cache.
        delivered = {
            "carrier": "DHL",
            "status": "DELIVERED",
        }
        assert (
            client.post(
                "/webhooks/logistics", json={**delivered, "tracking_key": "T-1"}
            ).status_code
            == 400
        )
        assert (
            client.post(
                "/webhooks/logistics",
                json={**delivered, "tracking_key": "T-2"},
                headers={"X-Wise-Webhook-Secret": "wrong"},
            ).status_code
            == 400
        )
        ok = client.post(
            "/webhooks/logistics",
            json={**delivered, "tracking_key": "T-3"},
            headers={"X-Wise-Webhook-Secret": "carrier-secret"},
        )
        assert ok.status_code == 200
        assert ok.json()["status"] == "ignored"  # auth passed, no funded contract
    finally:
        object.__setattr__(settings, "logistics_webhook_secret", None)


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
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    contract_id = _create_contract(client, buyer)
    _fund(client, contract_id, buyer)

    # EUR weakens to 0.90 USD before cancellation.
    fx.usd_values["EUR"] = Decimal("0.90")
    resp = client.post(f"/api/v1/escrow/{contract_id}/cancel", headers=_bearer(buyer))
    assert resp.status_code == 200
    assert resp.json()["status"] == "CANCELLED"

    accounts = client.get("/api/v1/accounts", headers=_bearer(buyer)).json()
    usd = next(a for a in accounts if a["currency"] == "USD")
    # 50000 - 11000 deposit + (10134.26 EUR * 0.90) refund
    assert Decimal(str(usd["balance"])) == Decimal("48120.83")


def test_contract_not_found_404(client: TestClient, session: Session) -> None:
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    resp = client.get(f"/api/v1/escrow/{uuid.uuid4()}", headers=_bearer(buyer))
    assert resp.status_code == 404
    assert resp.json()["error_code"] == "ERR_NOT_FOUND"


def test_escrow_requires_auth(client: TestClient) -> None:
    assert client.get("/api/v1/escrow").status_code == 401
    assert client.post("/api/v1/escrow", json={}).status_code == 401
    assert client.get("/api/v1/accounts").status_code == 401


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
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    contract_id = _create_contract(client, buyer)
    _fund(client, contract_id, buyer)
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
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    contract_id = _create_contract(client, buyer)
    _fund(client, contract_id, buyer)
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


def test_header_idempotency_key_scoped_to_endpoint(client: TestClient) -> None:
    """The same X-Idempotency-Key on a different endpoint must execute
    independently — never replay another route's response."""
    payload = {
        "email": "a@scoped.test",
        "password": "password123",
        "legal_name": "Scoped A",
        "ubo_tax_id": "U-A",
        "country": "US",
    }
    r1 = client.post("/api/v1/auth/register", json=payload, headers={"x-idempotency-key": "k1"})
    assert r1.status_code == 201
    drain_pending()

    r2 = client.post(
        "/api/v1/auth/register",
        json={**payload, "email": "b@scoped.test"},
        headers={"x-idempotency-key": "k1"},
    )
    # Same path + same key → replay, not re-execution.
    assert r2.headers.get("x-idempotent-replay") == "1"
    assert r2.json()["user"]["email"] == "a@scoped.test"

    r3 = client.post(
        "/webhooks/logistics",
        json={"carrier": "FEDEX", "status": "DELIVERED", "tracking_key": "x"},
        headers={"x-idempotency-key": "k1"},
    )
    # Different path + same key → executes independently.
    assert r3.headers.get("x-idempotent-replay") is None
    assert r3.status_code == 400
    drain_pending()


def test_server_error_releases_idempotency_claim(client: TestClient, session: Session) -> None:
    """A 5xx outcome must not be cached: the claim is released and a
    redelivery re-executes instead of replaying the failure."""
    calls = {"n": 0}

    def boom() -> JSONResponse:
        calls["n"] += 1
        return JSONResponse({"error": "boom"}, status_code=500)

    app.add_api_route("/__test_boom", boom, methods=["POST"])
    r1 = client.post("/__test_boom", json={}, headers={"x-idempotency-key": "bk"})
    assert r1.status_code == 500
    drain_pending()
    assert session.exec(select(IdempotencyRecord)).all() == []

    r2 = client.post("/__test_boom", json={}, headers={"x-idempotency-key": "bk"})
    assert r2.status_code == 500
    assert not r2.headers.get("x-idempotent-replay")
    assert calls["n"] == 2
    drain_pending()


def test_route_exception_releases_idempotency_claim(client: TestClient, session: Session) -> None:
    """An unhandled route exception releases the claim so retries are
    not permanently blocked by a stuck IN_FLIGHT record."""

    def explode() -> None:
        raise RuntimeError("exploded")

    app.add_api_route("/__test_explode", explode, methods=["POST"])
    with pytest.raises(RuntimeError, match="exploded"):
        client.post("/__test_explode", json={}, headers={"x-idempotency-key": "ek"})
    drain_pending()
    assert session.exec(select(IdempotencyRecord)).all() == []


def test_stale_inflight_claim_is_reclaimed(client: TestClient, session: Session) -> None:
    """An IN_FLIGHT claim older than the lease is abandoned state (the
    owner crashed) — a new delivery takes it over and executes."""
    env = _setup(session)
    buyer = env["buyer"]
    assert isinstance(buyer, User)
    contract_id = _create_contract(client, buyer)
    _fund(client, contract_id, buyer)

    body = json.dumps(
        {"action": "closed", "pull_request": {"merged": True, "html_url": PR_URL}},
        separators=(",", ":"),
    ).encode()
    key = hashlib.sha256(b"/webhooks/github" + body).hexdigest()
    session.add(
        IdempotencyRecord(
            idempotency_key=key,
            endpoint="/webhooks/github",
            status_code=-1,  # IN_FLIGHT
            response_body="",
            created_at=datetime.now(UTC) - timedelta(seconds=CLAIM_LEASE_SECONDS + 10),
        )
    )
    session.commit()

    response = client.post(
        "/webhooks/github",
        content=body,
        headers={"content-type": "application/json"},
    )
    assert response.status_code == 200
    assert response.json()["status"] == "settled"
    drain_pending()
