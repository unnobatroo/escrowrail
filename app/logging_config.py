"""Structured JSON logging.

Every record emits {timestamp, level, trace_id, contract_id, message}
plus any domain extras passed via ``extra={...}`` (account ids, amounts,
trace metadata) so transitions and ledger mutations are queryable.
"""

import json
import logging
from datetime import UTC, datetime
from typing import Any

from app.middleware.tracing import trace_id_var

_RESERVED = set(logging.makeLogRecord({}).__dict__)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "trace_id": trace_id_var.get("-"),
            "contract_id": getattr(record, "contract_id", None),
            "message": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _RESERVED and key not in payload:
                try:
                    json.dumps(value)
                    payload[key] = value
                except (TypeError, ValueError):
                    payload[key] = str(value)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_json_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())
