from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class ToolResult(BaseModel):
    model_config = ConfigDict(strict=True, frozen=True, extra="forbid")


class OCRResult(ToolResult):
    text: str = Field(min_length=1)
    model: str


class EmbeddingResult(ToolResult):
    vector: tuple[float, ...]
    dimensions: int = Field(ge=1, le=65536)
    model: str


class SearchHit(ToolResult):
    title: str
    url: str
    content: str | None = None
    snippet: str | None = None


class SearchResult(ToolResult):
    query: str
    results: tuple[SearchHit, ...]
    retrieved_at: datetime


class FetchResult(ToolResult):
    url: str
    title: str
    content: str = Field(min_length=1)
    retrieved_at: datetime
