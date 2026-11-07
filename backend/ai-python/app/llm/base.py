from typing import Protocol


class LLMProvider(Protocol):
    """Provider returns untrusted JSON text; the extraction boundary validates it."""

    async def generate_json(self, messages: list[dict[str, str]]) -> str: ...

    async def aclose(self) -> None: ...
