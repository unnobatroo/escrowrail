"""Application entrypoint: router wiring and domain-error translation."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.database import engine, init_db
from app.exceptions import WiseEscrowException
from app.logging_config import configure_json_logging
from app.middleware import (
    IdempotencyMiddleware,
    MetricsMiddleware,
    TraceIDMiddleware,
)
from app.routers import accounts, escrow, health, metrics, webhooks

configure_json_logging(settings.log_level)


@asynccontextmanager
async def lifespan(app_: FastAPI) -> AsyncIterator[None]:
    init_db()
    # Tests may pre-set app.state.engine to an isolated database; only
    # fall back to the module engine when nothing was configured.
    if getattr(app_.state, "engine", None) is None:
        app_.state.engine = engine
    yield


app = FastAPI(title="Wise Smart Escrow", version="0.1.0", lifespan=lifespan)
app.state.engine = engine

# Frontend dev server (Vite) origins.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# Last added runs outermost: Trace -> Metrics -> Idempotency -> route.
app.add_middleware(IdempotencyMiddleware)
app.add_middleware(MetricsMiddleware)
app.add_middleware(TraceIDMiddleware)


@app.exception_handler(WiseEscrowException)
async def domain_exception_handler(_request: Request, exc: WiseEscrowException) -> JSONResponse:
    content: dict[str, object] = {
        "error_code": exc.error_code,
        "message": exc.message,
        "context": exc.context,
    }
    if exc.client_code is not None:
        content["code"] = exc.client_code
    return JSONResponse(status_code=exc.http_status, content=content)


app.include_router(accounts.router)
app.include_router(escrow.router)
app.include_router(webhooks.router)
app.include_router(health.router)
app.include_router(metrics.router)


@app.get("/health")
def health_check() -> dict[str, str]:
    return {"status": "ok"}
