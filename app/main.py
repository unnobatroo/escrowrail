"""Application entrypoint: router wiring and domain-error translation."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.database import init_db
from app.exceptions import WiseEscrowException
from app.routers import accounts, escrow, webhooks

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(name)s %(levelname)s %(message)s",
)


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    init_db()
    yield


app = FastAPI(title="Escrowrail", version="0.1.0", lifespan=lifespan)


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


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
