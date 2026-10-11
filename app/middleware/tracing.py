"""Per-request trace-id propagation for structured logging."""

import contextvars
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

trace_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("trace_id", default="-")


class TraceIDMiddleware(BaseHTTPMiddleware):
    """Assigns a trace UUID per request (honoring inbound X-Trace-ID),
    exposes it to log records, and echoes it back on the response."""

    async def dispatch(self, request: Request, call_next: object) -> Response:
        trace_id = request.headers.get("x-trace-id") or str(uuid.uuid4())
        token = trace_id_var.set(trace_id)
        try:
            response: Response = await call_next(request)  # type: ignore[operator]
        finally:
            trace_id_var.reset(token)
        response.headers["X-Trace-ID"] = trace_id
        return response
