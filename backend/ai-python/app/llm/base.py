from collections.abc import AsyncIterator
from typing import Protocol


class LLMProvider(Protocol):
    """Provider returns untrusted JSON text; the extraction boundary validates it."""

    async def generate_json(
        self, messages: list[dict[str, str]], *, background: bool = False
    ) -> str: ...

    def stream_text(
        self, messages: list[dict[str, str]], *, json_mode: bool = False, long_form: bool = False
    ) -> AsyncIterator[str]: ...

    async def aclose(self) -> None: ...
