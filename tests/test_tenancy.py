"""Multi-tenant isolation: cross-tenant reads must not leak contracts,
accounts or ledgers; only the buyer may fund or cancel."""

from decimal import Decimal

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session

from app.models.auth import User
from app.services.auth_service import AuthService
from tests.conftest import make_account, make_entity, make_user


def _bearer(user: User) -> dict[str, str]:
    return {"Authorization": f"Bearer {AuthService.issue_token(user)}"}


@pytest.fixture(name="tenants")
def tenants_fixture(session: Session) -> dict[str, object]:
    """Three tenants: buyer (with USD funds), seller, stranger."""
    buyer_e = make_entity(session, "Buyer Org", "UB-BUY", "US")
    seller_e = make_entity(session, "Seller Org", "UB-SELL", "DE")
    stranger_e = make_entity(session, "Stranger Org", "UB-STR", "GB")
    buyer = make_user(session, buyer_e, "buyer@t.dev")
    seller = make_user(session, seller_e, "seller@t.dev")
    stranger = make_user(session, stranger_e, "stranger@t.dev")
    make_account(session, buyer_e.id, "USD", Decimal("50000"))
    make_account(session, buyer_e.id, "EUR", Decimal("0"))
    make_account(session, seller_e.id, "EUR", Decimal("0"))
    return {"buyer": buyer, "seller": seller, "stranger": stranger}


def _create(client: TestClient, buyer: User) -> str:
    resp = client.post(
        "/api/v1/escrow",
        headers=_bearer(buyer),
        json={
            "counterparty_email": "seller@t.dev",
            "target_amount": "5000",
            "tracking_type": "GITHUB_PR",
            "tracking_key": "tenancy#1",
        },
    )
    assert resp.status_code == 201, resp.text
    return str(resp.json()["id"])


def test_stranger_cannot_read_contract(client: TestClient, tenants: dict[str, object]) -> None:
    buyer = tenants["buyer"]
    stranger = tenants["stranger"]
    assert isinstance(buyer, User) and isinstance(stranger, User)
    cid = _create(client, buyer)
    resp = client.get(f"/api/v1/escrow/{cid}", headers=_bearer(stranger))
    assert resp.status_code == 404


def test_stranger_cannot_fund_or_cancel(client: TestClient, tenants: dict[str, object]) -> None:
    buyer = tenants["buyer"]
    stranger = tenants["stranger"]
    assert isinstance(buyer, User) and isinstance(stranger, User)
    cid = _create(client, buyer)
    fund = client.post(
        f"/api/v1/escrow/{cid}/fund",
        headers=_bearer(stranger),
        json={"deposit_amount": "6000"},
    )
    cancel = client.post(f"/api/v1/escrow/{cid}/cancel", headers=_bearer(stranger))
    assert fund.status_code == 404
    assert cancel.status_code == 404


def test_seller_cannot_fund_or_cancel(client: TestClient, tenants: dict[str, object]) -> None:
    """A party to the contract who is not the buyer gets 403 on writes."""
    buyer = tenants["buyer"]
    seller = tenants["seller"]
    assert isinstance(buyer, User) and isinstance(seller, User)
    cid = _create(client, buyer)
    fund = client.post(
        f"/api/v1/escrow/{cid}/fund",
        headers=_bearer(seller),
        json={"deposit_amount": "6000"},
    )
    cancel = client.post(f"/api/v1/escrow/{cid}/cancel", headers=_bearer(seller))
    assert fund.status_code == 403
    assert cancel.status_code == 403


def test_buyer_creates_and_funds(client: TestClient, tenants: dict[str, object]) -> None:
    buyer = tenants["buyer"]
    assert isinstance(buyer, User)
    cid = _create(client, buyer)
    resp = client.post(
        f"/api/v1/escrow/{cid}/fund",
        headers=_bearer(buyer),
        json={"deposit_amount": "5500"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "FUNDED"
    assert body["escrowed_amount"] is not None
    assert body["funded_at"] is not None


def test_seller_read_only_access(client: TestClient, tenants: dict[str, object]) -> None:
    buyer = tenants["buyer"]
    seller = tenants["seller"]
    assert isinstance(buyer, User) and isinstance(seller, User)
    cid = _create(client, buyer)
    client.post(
        f"/api/v1/escrow/{cid}/fund",
        headers=_bearer(buyer),
        json={"deposit_amount": "5500"},
    )
    detail = client.get(f"/api/v1/escrow/{cid}", headers=_bearer(seller))
    assert detail.status_code == 200
    assert detail.json()["role"] == "seller"
    audit = client.get(f"/api/v1/escrow/{cid}/audit", headers=_bearer(seller))
    assert audit.status_code == 200
    assert len(audit.json()) == 3  # fee + deposit + conversion


def test_contract_listing_is_scoped(client: TestClient, tenants: dict[str, object]) -> None:
    buyer = tenants["buyer"]
    seller = tenants["seller"]
    stranger = tenants["stranger"]
    assert isinstance(buyer, User) and isinstance(seller, User)
    assert isinstance(stranger, User)
    _create(client, buyer)
    mine = client.get("/api/v1/escrow", headers=_bearer(buyer)).json()
    theirs = client.get("/api/v1/escrow", headers=_bearer(seller)).json()
    none = client.get("/api/v1/escrow", headers=_bearer(stranger)).json()
    assert len(mine) == 1 and mine[0]["role"] == "buyer"
    assert len(theirs) == 1 and theirs[0]["role"] == "seller"
    assert none == []


def test_accounts_are_tenant_isolated(client: TestClient, session: Session) -> None:
    """User B cannot read or inspect User A's account or its ledger."""
    env_a = make_entity(session, "Org A", "UB-A", "US")
    env_b = make_entity(session, "Org B", "UB-B", "DE")
    user_a = make_user(session, env_a, "a@t.dev")
    user_b = make_user(session, env_b, "b@t.dev")
    acc_a = make_account(session, env_a.id, "USD", Decimal("1000"))

    # B's own listing never contains A's account.
    mine = client.get("/api/v1/accounts", headers=_bearer(user_b)).json()
    assert all(a["id"] != str(acc_a.id) for a in mine)

    # B cannot fetch A's account directly (404 — no existence leak).
    assert client.get(f"/api/v1/accounts/{acc_a.id}", headers=_bearer(user_b)).status_code == 404
    # B cannot read A's account ledger either (403 — tenancy breach).
    assert (
        client.get(f"/api/v1/accounts/{acc_a.id}/ledger", headers=_bearer(user_b)).status_code
        == 403
    )
    # A reads its own fine.
    assert client.get(f"/api/v1/accounts/{acc_a.id}", headers=_bearer(user_a)).status_code == 200


def test_counterparty_resolution_and_invite(client: TestClient, tenants: dict[str, object]) -> None:
    buyer = tenants["buyer"]
    assert isinstance(buyer, User)
    # Known counterparty resolves with its org + UBO for the AML guardrail.
    ok = client.get(
        "/api/v1/escrow/counterparty",
        params={"email": "seller@t.dev"},
        headers=_bearer(buyer),
    )
    assert ok.status_code == 200
    assert ok.json()["ubo_tax_id"] == "UB-SELL"
    assert ok.json()["legal_name"] == "Seller Org"

    # Unknown email → 404 on resolve, and create flags an external invite.
    assert (
        client.get(
            "/api/v1/escrow/counterparty",
            params={"email": "ghost@nowhere.dev"},
            headers=_bearer(buyer),
        ).status_code
        == 404
    )
    resp = client.post(
        "/api/v1/escrow",
        headers=_bearer(buyer),
        json={
            "counterparty_email": "ghost@nowhere.dev",
            "target_amount": "1000",
            "tracking_type": "GITHUB_PR",
            "tracking_key": "k",
        },
    )
    assert resp.status_code == 404


def test_register_login_me_flow(client: TestClient) -> None:
    reg = client.post(
        "/api/v1/auth/register",
        json={
            "email": "New@Org.dev",
            "password": "supersecret1",
            "legal_name": "New Org",
            "ubo_tax_id": "UB-NEW",
            "country": "us",
        },
    )
    assert reg.status_code == 201
    token = reg.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}
    me = client.get("/api/v1/auth/me", headers=headers)
    assert me.status_code == 200
    assert me.json()["email"] == "new@org.dev"  # normalized
    assert me.json()["entity"]["legal_name"] == "New Org"

    # Registration boots ledger-funded USD+EUR sandbox accounts.
    accounts = client.get("/api/v1/accounts", headers=headers).json()
    balances = {a["currency"]: Decimal(str(a["balance"])) for a in accounts}
    assert balances == {"USD": Decimal("50000.00"), "EUR": Decimal("10000.00")}

    # Duplicate email rejected; login works with OAuth2 form.
    dup = client.post(
        "/api/v1/auth/register",
        json={
            "email": "new@org.dev",
            "password": "supersecret1",
            "legal_name": "New Org",
            "ubo_tax_id": "UB-NEW",
            "country": "US",
        },
    )
    assert dup.status_code == 409
    login = client.post(
        "/api/v1/auth/login",
        data={"username": "new@org.dev", "password": "supersecret1"},
    )
    assert login.status_code == 200
    assert (
        client.post(
            "/api/v1/auth/login",
            data={"username": "new@org.dev", "password": "wrong-password"},
        ).status_code
        == 401
    )
