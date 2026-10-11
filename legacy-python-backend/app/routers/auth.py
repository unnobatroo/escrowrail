"""Authentication endpoints: register, login (OAuth2), current user."""

from datetime import datetime
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends
from fastapi.security import OAuth2PasswordRequestForm
from pydantic import BaseModel, Field

from app.dependencies import CurrentUser, TaskQueueDep, get_auth_service
from app.exceptions import NotFoundError
from app.models.auth import User
from app.models.entities import Entity
from app.services.auth_service import AuthService
from app.tasks import send_welcome_email

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])

AuthServiceDep = Annotated[AuthService, Depends(get_auth_service)]


class RegisterRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254, pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
    password: str = Field(min_length=8, max_length=128)
    legal_name: str = Field(min_length=1, max_length=200)
    ubo_tax_id: str = Field(min_length=1, max_length=64)
    country: str = Field(min_length=2, max_length=2)


class EntityView(BaseModel):
    id: UUID
    legal_name: str
    ubo_tax_id: str
    country: str


class UserView(BaseModel):
    id: UUID
    email: str
    is_active: bool
    created_at: datetime
    entity: EntityView


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"


class RegisterResponse(TokenResponse):
    user: UserView


def _user_view(user: User, entity: Entity) -> UserView:
    return UserView(
        id=user.id,
        email=user.email,
        is_active=user.is_active,
        created_at=user.created_at,
        entity=EntityView(
            id=entity.id,
            legal_name=entity.legal_name,
            ubo_tax_id=entity.ubo_tax_id,
            country=entity.country,
        ),
    )


@router.post("/register", status_code=201)
def register(
    body: RegisterRequest,
    service: AuthServiceDep,
    tasks: TaskQueueDep,
) -> RegisterResponse:
    user, entity = service.register(
        email=body.email,
        password=body.password,
        legal_name=body.legal_name,
        ubo_tax_id=body.ubo_tax_id,
        country=body.country.upper(),
    )
    # External IO is queued post-commit, never inside the ledger txn.
    tasks.enqueue(send_welcome_email, user.email, entity.legal_name)
    return RegisterResponse(
        access_token=service.issue_token(user),
        user=_user_view(user, entity),
    )


@router.post("/login")
def login(
    form: Annotated[OAuth2PasswordRequestForm, Depends()],
    service: AuthServiceDep,
) -> TokenResponse:
    user = service.authenticate(form.username, form.password)
    return TokenResponse(access_token=service.issue_token(user))


@router.get("/me")
def me(user: CurrentUser, service: AuthServiceDep) -> UserView:
    entity = service.entities.get(user.entity_id)
    if entity is None:  # pragma: no cover - FK guarantees existence
        raise NotFoundError("Linked entity not found", context={"entity_id": str(user.entity_id)})
    return _user_view(user, entity)
