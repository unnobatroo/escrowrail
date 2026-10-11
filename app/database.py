"""Database engine and session management.

SQLite is hardened for concurrent writers: `timeout=30.0` lets blocked
writers wait instead of erroring, and every transaction is promoted to
`BEGIN IMMEDIATE` so a writer holds the reserved lock for its whole
unit of work. On PostgreSQL, serialization is provided by
`.with_for_update()` row locks instead.
"""

from collections.abc import Iterator

from sqlalchemy import Connection, create_engine, event
from sqlalchemy.engine import Engine
from sqlmodel import Session, SQLModel

from app.config import settings


def build_engine(database_url: str) -> Engine:
    connect_args = (
        {"check_same_thread": False, "timeout": 30.0} if database_url.startswith("sqlite") else {}
    )
    engine = create_engine(database_url, connect_args=connect_args)

    if database_url.startswith("sqlite"):

        @event.listens_for(engine, "begin")
        def _begin_immediate(conn: Connection) -> None:
            conn.exec_driver_sql("BEGIN IMMEDIATE")

    return engine


engine = build_engine(settings.database_url)


def init_db() -> None:
    # Import models so metadata is populated before create_all.
    import app.models  # noqa: F401

    SQLModel.metadata.create_all(engine)


def get_session() -> Iterator[Session]:
    with Session(engine) as session:
        yield session
