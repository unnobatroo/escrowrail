"""Decoupled background-task interface.

Slow external IO (counterparty invite emails, settlement webhook
notifications) must not block the synchronous ledger transaction path.
`ITaskQueue` is the seam: `BackgroundTaskQueue` defers work to FastAPI's
in-process BackgroundTasks for the MVP; a Redis/Celery/SQS adapter can
replace it without touching services or routers.
"""

import logging
from collections.abc import Callable
from typing import Any, Protocol

from fastapi import BackgroundTasks

logger = logging.getLogger("wise_escrow.tasks")


class ITaskQueue(Protocol):
    def enqueue(self, fn: Callable[..., None], *args: Any, **kwargs: Any) -> None: ...


class BackgroundTaskQueue:
    """ITaskQueue backed by FastAPI BackgroundTasks (post-response)."""

    def __init__(self, tasks: BackgroundTasks) -> None:
        self._tasks = tasks

    def enqueue(self, fn: Callable[..., None], *args: Any, **kwargs: Any) -> None:
        self._tasks.add_task(fn, *args, **kwargs)


# ---- task payloads (simulated external IO; log-only in MVP) ----


def send_counterparty_invite(email: str, sender_entity_name: str) -> None:
    logger.info(
        "counterparty_invite_dispatched",
        extra={"counterparty_email": email, "sender": sender_entity_name},
    )


def send_welcome_email(email: str, entity_name: str) -> None:
    logger.info(
        "welcome_email_dispatched",
        extra={"recipient": email, "entity": entity_name},
    )


def dispatch_settlement_notification(contract_id: str, recipient_entity_id: str) -> None:
    logger.info(
        "settlement_notification_dispatched",
        extra={"contract_id": contract_id, "recipient_entity_id": recipient_entity_id},
    )
