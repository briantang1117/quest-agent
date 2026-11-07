from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse


class ServiceError(Exception):
    def __init__(self, status: int, code: int, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def envelope(trace_id: str, data: Any = None, code: int = 0, message: str = "OK") -> dict:
    return {"code": code, "message": message, "data": data, "traceId": trace_id}


def error_response(request: Request, error: ServiceError) -> JSONResponse:
    return JSONResponse(
        status_code=error.status,
        content=envelope(request.state.trace_id, code=error.code, message=error.message),
    )
