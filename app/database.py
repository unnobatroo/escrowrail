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
    if database_url.startswith("postgresql://"):
        # Bare postgresql:// selects psycopg2; we ship psycopg 3.
        database_url = "postgresql+psycopg://" + database_url[len("postgresql://") :]
    if database_url.startswith("sqlite"):
        engine = create_engine(
            database_url,
            connect_args={"check_same_thread": False, "timeout": 30.0},
        )

        @event.listens_for(engine, "begin")
        def _begin_immediate(conn: Connection) -> None:
            conn.exec_driver_sql("BEGIN IMMEDIATE")

        return engine

    # PostgreSQL (and other network DBs): bounded pool sized for
    # horizontal replicas; row locks replace BEGIN IMMEDIATE.
    return create_engine(
        database_url,
        pool_size=20,
        max_overflow=10,
        pool_pre_ping=True,
    )


engine = build_engine(settings.database_url)


def init_db() -> None:
    # Import models so metadata is populated before create_all.
    import app.models  # noqa: F401

    SQLModel.metadata.create_all(engine)
    _migrate(engine)


def _migrate(eng: Engine) -> None:
    """Idempotent column additions for databases created before a field
    existed. create_all never alters existing tables, so additive columns
    are applied explicitly until a real migration tool is introduced."""
    from sqlalchemy import DateTime, inspect, text

    existing = {c["name"] for c in inspect(eng).get_columns("escrow_contracts")}
    if "funded_at" not in existing:
        col_type = DateTime().compile(dialect=eng.dialect)
        with eng.begin() as conn:
            conn.execute(text(f"ALTER TABLE escrow_contracts ADD COLUMN funded_at {col_type}"))


def get_session() -> Iterator[Session]:
    with Session(engine) as session:
        yield session
