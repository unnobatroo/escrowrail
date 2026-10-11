"""Idempotency engine + webhook replay protection (ASGI middleware).

External senders guarantee at-least-once delivery, so duplicate calls
must never re-execute ledger operations.

- Dedup key: `X-Idempotency-Key` header on any POST, or a deterministic
  `sha256(endpoint + raw_body)` for `/webhooks/*` endpoints.
- The first request *claims* the key (unique-constraint insert) and
  executes; parallel duplicates wait for the winner to finish and then
  replay its stored response (`X-Idempotent-Replay: 1`).
- Webhook deliveries are audited in `webhook_delivery_logs`
  (SUCCESS / DUPLICATE / FAILED).
- Replay protection: webhooks carrying a delivery timestamp older than
  300 seconds are rejected.
"""

import asyncio
import hashlib
import json
import logging
import threading
import time
from datetime import UTC, datetime
from typing import Any

from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select
from starlette.concurrency import run_in_threadpool
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from app.database import engine as default_engine
from app.models.idempotency import IN_FLIGHT, IdempotencyRecord, WebhookDeliveryLog

logger = logging.getLogger("wise_escrow.idempotency")

REPLAY_WINDOW_SECONDS = 300
POLL_TIMEOUT_SECONDS = 30.0
POLL_INTERVAL_SECONDS = 0.05
# An IN_FLIGHT claim older than this is treated as abandoned (crashed
# owner) and can be reclaimed. Deliberately much longer than the poll
# timeout: reclaiming a claim whose owner is still executing would
# double-execute ledger operations.
CLAIM_LEASE_SECONDS = 120.0

_PROVIDER_BY_ENDPOINT = {"github": "github", "logistics": "dhl"}

# Post-response DB writes run on detached threads (see dispatch); tests
# drain this registry to assert on delivery logs deterministically.
_pending: list[threading.Thread] = []


def _spawn(target: Any, args: tuple[Any, ...]) -> None:
    thread = threading.Thread(target=target, args=args, daemon=True)
    _pending.append(thread)
    thread.start()


def drain_pending(timeout: float = 30.0) -> None:
    """Join all outstanding completion-writer threads (test helper)."""
    deadline = time.time() + timeout
    while _pending:
        thread = _pending.pop()
        thread.join(max(0.0, deadline - time.time()))


def _payload_hash(endpoint: str, body: bytes) -> str:
    return hashlib.sha256(endpoint.encode() + body).hexdigest()


def _is_stale(record: IdempotencyRecord) -> bool:
    """True if an IN_FLIGHT claim is older than the reclaim lease."""
    if record.status_code != IN_FLIGHT:
        return False
    created = record.created_at
    if created.tzinfo is None:  # SQLite returns naive datetimes
        created = created.replace(tzinfo=UTC)
    return (datetime.now(UTC) - created).total_seconds() > CLAIM_LEASE_SECONDS


def _delivery_timestamp(request: Request, payload: Any) -> float | None:
    """Extract a unix delivery timestamp from headers or the JSON body."""
    for header in ("x-delivery-timestamp", "x-request-timestamp"):
        raw = request.headers.get(header)
        if raw is not None:
            try:
                return float(raw)
            except ValueError:
                return None
    if isinstance(payload, dict):
        raw = payload.get("timestamp") or payload.get("delivered_at")
        if raw is not None:
            try:
                return float(raw)
            except (TypeError, ValueError):
                return None
    return None


class IdempotencyMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        if request.method != "POST":
            return await call_next(request)

        body = await request.body()

        async def receive() -> dict[str, Any]:
            return {"type": "http.request", "body": body, "more_body": False}

        request = Request(request.scope, receive)

        path = request.url.path
        is_webhook = path.startswith("/webhooks")
        try:
            payload: Any = json.loads(body) if body else None
        except json.JSONDecodeError:
            payload = None

        engine: Engine = getattr(request.app.state, "engine", default_engine)
        if is_webhook:
            ts = _delivery_timestamp(request, payload)
            if ts is not None and time.time() - ts > REPLAY_WINDOW_SECONDS:
                await run_in_threadpool(
                    self._log_delivery,
                    engine,
                    path,
                    payload,
                    body,
                    "FAILED",
                    "stale delivery timestamp",
                )
                return self._reject(
                    "Webhook delivery timestamp older than 300 seconds",
                    {"timestamp": str(ts)},
                )

        header_key = request.headers.get("x-idempotency-key")
        dedup_key: str | None
        if header_key:
            # Scope client keys to the endpoint: the same key sent to a
            # different path must execute independently, never replay
            # another route's response.
            dedup_key = f"{path}:{header_key}"
        else:
            dedup_key = _payload_hash(path, body) if is_webhook else None

        if dedup_key is None:
            response = await call_next(request)
            return await self._capture(response, is_webhook, path, engine, payload, body)

        # All DB access runs in the threadpool: a blocking call on the
        # event loop would freeze dependency teardown and deadlock on
        # the write lock the route's session still holds.
        while True:
            record = await run_in_threadpool(self._get_record, engine, dedup_key)
            if record is not None and record.status_code != IN_FLIGHT:
                return await self._replay(record, engine, path, payload, body)
            if record is not None and not _is_stale(record):
                # Live claim owned by another request — wait for it.
                record = await self._wait_for(engine, dedup_key)
                if record is not None and record.status_code != IN_FLIGHT:
                    return await self._replay(record, engine, path, payload, body)
                if record is not None and not _is_stale(record):
                    return self._reject("Concurrent duplicate in flight timed out", {})
                # Claim was released or its lease expired — take it over.
            if record is not None:
                await run_in_threadpool(self._release, engine, dedup_key)
            try:
                await run_in_threadpool(self._claim, engine, dedup_key, path)
                break  # this request won the claim
            except IntegrityError:
                continue  # lost the claim race — re-read and replay/wait

        # This request won the claim — execute, cache, return.
        try:
            response = await call_next(request)
            response_body, response = await self._read_body(response)
        except Exception:
            # A crashed execution must not wedge the key forever.
            await run_in_threadpool(self._release, engine, dedup_key)
            raise
        if response.status_code >= 500:
            # Transient failures are never cached: release the claim so a
            # provider redelivery re-executes instead of replaying a 5xx.
            _spawn(
                self._release_and_log,
                (engine, dedup_key, is_webhook, path, payload, response_body),
            )
            return response
        # Completion must NOT be awaited: the route's DI session keeps its
        # write transaction until the response is fully streamed (Starlette
        # parks the app task on response_sent), so awaiting a write here
        # deadlocks. The detached thread blocks on BEGIN IMMEDIATE until
        # teardown releases the lock; duplicates see IN_FLIGHT and poll.
        _spawn(
            self._finish,
            (engine, dedup_key, response.status_code, response_body, is_webhook, path, payload),
        )
        return response

    def _release_and_log(
        self,
        engine: Engine,
        key: str,
        is_webhook: bool,
        path: str,
        payload: Any,
        raw: bytes,
    ) -> None:
        try:
            self._release(engine, key)
        except Exception:
            logger.exception("idempotency_release_failed")
        if is_webhook:
            self._log_delivery(engine, path, payload, raw, "FAILED", raw.decode("utf-8", "replace"))

    def _finish(
        self,
        engine: Engine,
        key: str,
        status: int,
        body: bytes,
        is_webhook: bool,
        path: str,
        payload: Any,
    ) -> None:
        try:
            self._complete(engine, key, status, body)
        except Exception:
            logger.exception("idempotency_complete_failed")
        if is_webhook:
            self._log_delivery(
                engine,
                path,
                payload,
                body,
                "SUCCESS" if status < 400 else "FAILED",
                body.decode("utf-8", "replace"),
            )

    def _get_record(self, engine: Engine, key: str) -> IdempotencyRecord | None:
        with Session(engine) as session:
            stmt = select(IdempotencyRecord).where(IdempotencyRecord.idempotency_key == key)
            record = session.exec(stmt).first()
            if record is not None:
                session.expunge(record)
            return record

    def _claim(self, engine: Engine, key: str, endpoint: str) -> None:
        with Session(engine) as session:
            session.add(
                IdempotencyRecord(
                    idempotency_key=key,
                    endpoint=endpoint,
                    status_code=IN_FLIGHT,
                    response_body="",
                )
            )
            session.commit()

    def _release(self, engine: Engine, key: str) -> None:
        """Delete an IN_FLIGHT claim so a later request can re-execute."""
        with Session(engine) as session:
            stmt = select(IdempotencyRecord).where(
                IdempotencyRecord.idempotency_key == key,
                IdempotencyRecord.status_code == IN_FLIGHT,
            )
            record = session.exec(stmt).first()
            if record is not None:
                session.delete(record)
                session.commit()

    def _complete(self, engine: Engine, key: str, status: int, body: bytes) -> None:
        with Session(engine) as session:
            stmt = select(IdempotencyRecord).where(IdempotencyRecord.idempotency_key == key)
            record = session.exec(stmt).first()
            if record is not None:
                record.status_code = status
                record.response_body = body.decode("utf-8", "replace")
                session.add(record)
                session.commit()

    async def _wait_for(self, engine: Engine, key: str) -> IdempotencyRecord | None:
        """Poll until the claim completes, is released, or times out."""
        waited = 0.0
        record: IdempotencyRecord | None = None
        while waited < POLL_TIMEOUT_SECONDS:
            record = await run_in_threadpool(self._get_record, engine, key)
            if record is None or record.status_code != IN_FLIGHT:
                return record
            await asyncio.sleep(POLL_INTERVAL_SECONDS)
            waited += POLL_INTERVAL_SECONDS
        return record  # still IN_FLIGHT at timeout — caller checks staleness

    async def _read_body(self, response: Response) -> tuple[bytes, Response]:
        chunks: list[bytes] = []
        async for chunk in response.body_iterator:  # type: ignore[attr-defined]
            chunks.append(chunk if isinstance(chunk, bytes) else chunk.encode())
        body = b"".join(chunks)
        rebuilt = Response(
            content=body,
            status_code=response.status_code,
            headers=dict(response.headers),
            media_type=response.media_type,
        )
        return body, rebuilt

    async def _capture(
        self,
        response: Response,
        is_webhook: bool,
        path: str,
        engine: Engine,
        payload: Any,
        raw: bytes,
    ) -> Response:
        """Non-dedup path: pass through, but still audit webhook traffic."""
        body, rebuilt = await self._read_body(response)
        if is_webhook:
            # Detached for the same post-response lock reason as _finish.
            _spawn(
                self._log_delivery,
                (
                    engine,
                    path,
                    payload,
                    raw,
                    "SUCCESS" if response.status_code < 400 else "FAILED",
                    body.decode("utf-8", "replace"),
                ),
            )
        return rebuilt

    async def _replay(
        self,
        record: IdempotencyRecord,
        engine: Engine,
        path: str,
        payload: Any,
        raw: bytes,
    ) -> Response:
        if path.startswith("/webhooks"):
            await run_in_threadpool(
                self._log_delivery, engine, path, payload, raw, "DUPLICATE", None
            )
        logger.info(
            "idempotent_replay",
            extra={"idempotency_key": record.idempotency_key},
        )
        return Response(
            content=record.response_body,
            status_code=record.status_code,
            media_type="application/json",
            headers={"X-Idempotent-Replay": "1"},
        )

    @staticmethod
    def _reject(message: str, context: dict[str, str]) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content={
                "error_code": "ERR_WEBHOOK_VALIDATION",
                "message": message,
                "context": context,
            },
        )

    def _log_delivery(
        self,
        engine: Engine,
        path: str,
        payload: Any,
        raw: bytes,
        status: str,
        error: str | None,
    ) -> None:
        try:
            endpoint = path.rsplit("/", 1)[-1]
            provider = _PROVIDER_BY_ENDPOINT.get(endpoint, "unknown")
            event_type = "unknown"
            if isinstance(payload, dict):
                event_type = str(
                    payload.get("action")
                    or payload.get("status")
                    or payload.get("event")
                    or "unknown"
                )
            with Session(engine) as session:
                session.add(
                    WebhookDeliveryLog(
                        provider=provider,
                        event_type=event_type,
                        payload_hash=hashlib.sha256(raw).hexdigest(),
                        raw_payload=raw.decode("utf-8", "replace"),
                        processed_status=status,
                        error_message=error,
                    )
                )
                session.commit()
        except Exception as exc:  # audit must never break the request
            logger.warning("webhook_delivery_log_failed", extra={"error": str(exc)})
