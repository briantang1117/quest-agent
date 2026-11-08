"""独立能力适配器；不接入路由、不执行数据库写入或自动付费重试。"""

from app.providers.ark import ArkEmbeddingProvider, ArkOCRProvider
from app.providers.deepseek import DeepSeekFetchProvider, DeepSeekSearchProvider
from app.providers.models import EmbeddingResult, FetchResult, OCRResult, SearchHit, SearchResult

__all__ = [
    "ArkEmbeddingProvider",
    "ArkOCRProvider",
    "DeepSeekFetchProvider",
    "DeepSeekSearchProvider",
    "EmbeddingResult",
    "FetchResult",
    "OCRResult",
    "SearchHit",
    "SearchResult",
]
