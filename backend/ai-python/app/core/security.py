import hashlib
import hmac
import re
import threading
import time

from fastapi import Request

from app.core.config import Settings
from app.core.errors import ServiceError

WINDOW_SECONDS = 300


class NonceStore:
    """Single-process replay protection. Replace with Redis SET NX EX before scaling out."""

    def __init__(self):
        self._used: dict[str, float] = {}
        self._lock = threading.Lock()

    def claim(self, nonce: str, expires_at: float, now: float) -> bool:
        with self._lock:
            self._used = {key: expiry for key, expiry in self._used.items() if expiry >= now}
            if nonce in self._used:
                return False
            self._used[nonce] = expires_at
            return True


async def authenticate(request: Request, settings: Settings, nonces: NonceStore) -> None:
    if not settings.internal_security_configured:
        raise ServiceError(503, 50301, "Internal authentication is not configured")

    def unauthorized() -> ServiceError:
        return ServiceError(401, 40101, "Invalid internal authentication or replayed request")

    names = ("X-Service-Token", "X-Timestamp", "X-Nonce", "X-Signature")
    if any(len(request.headers.getlist(name)) != 1 for name in names):
        raise unauthorized()
    token, timestamp, nonce, signature = (request.headers[name] for name in names)
    if (
        not re.fullmatch(r"[0-9]{1,12}", timestamp)
        or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", nonce)
        or not re.fullmatch(r"[0-9a-f]{64}", signature)
    ):
        raise unauthorized()
    now = time.time()
    stamp = int(timestamp)
    if abs(now - stamp) > WINDOW_SECONDS:
        raise unauthorized()
    # All comparison inputs are bytes so malformed Unicode cannot cause compare_digest errors.
    assert settings.internal_service_token is not None
    assert settings.internal_hmac_secret is not None
    token_ok = hmac.compare_digest(
        token.encode(),
        settings.internal_service_token.get_secret_value().encode(),
    )
    raw_body = await request.body()
    message = "\n".join(
        (
            timestamp,
            nonce,
            request.method.upper(),
            request.url.path,
            hashlib.sha256(raw_body).hexdigest(),
        )
    )
    expected = hmac.new(
        settings.internal_hmac_secret.get_secret_value().encode(),
        message.encode(),
        hashlib.sha256,
    ).hexdigest()
    signature_ok = hmac.compare_digest(signature, expected)
    if not (token_ok and signature_ok):
        raise unauthorized()
    # A future-dated request stays replayable until timestamp + window, not now + window.
    if not nonces.claim(nonce, stamp + WINDOW_SECONDS, now):
        raise unauthorized()
