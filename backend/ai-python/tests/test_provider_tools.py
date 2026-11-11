import asyncio
import base64
import json

import httpx
import pytest
from pydantic import SecretStr

from app.core.errors import ServiceError
from app.providers import (
    ArkEmbeddingProvider,
    ArkOCRProvider,
    DeepSeekFetchProvider,
    DeepSeekSearchProvider,
)
from tests.conftest import settings

PNG = b"\x89PNG\r\n\x1a\n" + b"synthetic-image"


def tool_settings(**overrides):
    # 使用完整Settings校验，始终只用合成凭据。
    values = {
        "ocr_api_base_url": "https://ark.example/api/plan/v3",
        "ocr_api_key": SecretStr("ocr-only-secret"),
        "ocr_model": "doubao-seed-2.1-pro",
        "embedding_base_url": "https://ark.example/api/plan/v3",
        "embedding_api_key": SecretStr("embedding-only-secret"),
        "embedding_model": "doubao-embedding-vision",
        "search_api_base_url": "https://search.example/v1",
        "search_api_key": SecretStr("search-only-secret"),
        "fetch_api_base_url": "https://fetch.example/v1",
        "fetch_api_key": SecretStr("fetch-only-secret"),
    }
    values.update(overrides)
    return settings(**values)


def call(provider, method, *args, **kwargs):
    async def execute():
        async with provider:
            return await getattr(provider, method)(*args, **kwargs)

    return asyncio.run(execute())


def transport_json(payload, status=200):
    return httpx.MockTransport(lambda request: httpx.Response(status, json=payload))


async def public_dns(host):
    return ["93.184.216.34", "2606:4700:4700::1111"]


def test_ocr_uses_own_key_and_image_data_url():
    def handler(request):
        assert str(request.url) == "https://ark.example/api/plan/v3/chat/completions"
        assert request.headers["Authorization"] == "Bearer ocr-only-secret"
        body = json.loads(request.content)
        assert body["model"] == "doubao-seed-2.1-pro"
        image = body["messages"][0]["content"][1]["image_url"]["url"]
        assert image == "data:image/png;base64," + base64.b64encode(PNG).decode()
        return httpx.Response(
            200, json={"choices": [{"finish_reason": "stop", "message": {"content": "原文"}}]}
        )

    provider = ArkOCRProvider(tool_settings(), transport=httpx.MockTransport(handler))
    result = call(provider, "recognize", PNG)
    assert result.text == "原文"
    assert result.model == "doubao-seed-2.1-pro"
    assert provider._client.is_closed
    assert provider._client.follow_redirects is False
    assert provider._client._trust_env is False
    assert "bbox" not in result.model_dump()


@pytest.mark.parametrize(
    "image", [b"bad", b"", "https://example.com/x.png", "/tmp/x.png", PNG * 500000]
)
def test_ocr_rejects_unsafe_input_before_network(image):
    def unexpected(request):
        pytest.fail("无效图片不得外发")

    with pytest.raises(ServiceError) as error:
        call(
            ArkOCRProvider(tool_settings(), transport=httpx.MockTransport(unexpected)),
            "recognize",
            image,
        )
    assert error.value.status == 400


@pytest.mark.parametrize("image,mime", [(b"\xff\xd8\xffx", "jpeg"), (b"RIFFxxxxWEBPx", "webp")])
def test_ocr_supports_jpeg_and_webp(image, mime):
    def handler(request):
        assert f"data:image/{mime};base64," in request.content.decode()
        return httpx.Response(
            200, json={"choices": [{"finish_reason": "stop", "message": {"content": "正文"}}]}
        )

    assert (
        call(
            ArkOCRProvider(tool_settings(), transport=httpx.MockTransport(handler)),
            "recognize",
            image,
        ).text
        == "正文"
    )


@pytest.mark.parametrize(
    "choice",
    [
        {"finish_reason": "length", "message": {"content": "截断正文"}},
        {"finish_reason": "stop", "message": {"content": "  "}},
        {"finish_reason": "stop", "message": {"content": []}},
        {"message": {"content": "缺完成标记"}},
    ],
)
def test_ocr_rejects_incomplete_or_invalid_completion(choice):
    with pytest.raises(ServiceError) as error:
        call(
            ArkOCRProvider(tool_settings(), transport=transport_json({"choices": [choice]})),
            "recognize",
            PNG,
        )
    assert error.value.status == 502


def test_embedding_accepts_real_object_shape_and_2048_dimensions():
    def handler(request):
        assert request.url.path.endswith("/embeddings/multimodal")
        assert request.headers["Authorization"] == "Bearer embedding-only-secret"
        assert json.loads(request.content) == {
            "model": "doubao-embedding-vision",
            "input": [{"type": "text", "text": "公开文本"}],
        }
        return httpx.Response(200, json={"data": {"embedding": [0.5] * 2048}})

    result = call(
        ArkEmbeddingProvider(tool_settings(), transport=httpx.MockTransport(handler)),
        "embed",
        "公开文本",
    )
    assert result.dimensions == 2048
    assert result.vector == (0.5,) * 2048


@pytest.mark.parametrize(
    "vector", [[], [True], ["1.0"], [None], [float("nan")], [float("inf")], [0, 0.0], [0] * 65537]
)
def test_embedding_rejects_invalid_vectors(vector):
    # 非有限值用原始JSON模拟，httpx的json编码器主动拒绝NaN。
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            content=json.dumps({"data": {"embedding": vector}}),
            headers={"Content-Type": "application/json"},
        )
    )
    with pytest.raises(ServiceError) as error:
        call(ArkEmbeddingProvider(tool_settings(), transport=transport), "embed", "文本")
    assert error.value.status == 502


@pytest.mark.parametrize("payload", [{"data": [{"embedding": [1]}]}, {"data": {}}, {}, []])
def test_embedding_rejects_wrong_response_schema(payload):
    with pytest.raises(ServiceError):
        call(
            ArkEmbeddingProvider(tool_settings(), transport=transport_json(payload)),
            "embed",
            "文本",
        )


def test_embedding_checks_explicit_dimension_and_accepts_integer_numbers():
    result = call(
        ArkEmbeddingProvider(
            tool_settings(embedding_dimensions=2),
            transport=transport_json({"data": {"embedding": [1, 2.5]}}),
        ),
        "embed",
        "文本",
    )
    assert result.vector == (1.0, 2.5)
    with pytest.raises(ServiceError):
        call(
            ArkEmbeddingProvider(
                tool_settings(embedding_dimensions=1024),
                transport=transport_json({"data": {"embedding": [1]}}),
            ),
            "embed",
            "文本",
        )


@pytest.mark.parametrize("text", ["", "  ", None, True])
def test_embedding_rejects_invalid_text(text):
    with pytest.raises(ServiceError) as error:
        call(ArkEmbeddingProvider(tool_settings(), transport=transport_json({})), "embed", text)
    assert error.value.status == 400


def test_search_request_and_source_metadata():
    def handler(request):
        assert str(request.url) == "https://search.example/v1/tools/search"
        assert request.headers["Authorization"] == "Bearer search-only-secret"
        assert json.loads(request.content) == {
            "query": "现行法规",
            "count": 2,
            "need_content": True,
        }
        return httpx.Response(
            200,
            json={
                "results": [
                    {"title": "法规", "url": "https://example.com/law", "content": "不可信网页文本"}
                ]
            },
        )

    result = call(
        DeepSeekSearchProvider(tool_settings(), transport=httpx.MockTransport(handler)),
        "search",
        "现行法规",
        count=2,
        need_content=True,
    )
    assert result.query == "现行法规"
    assert result.results[0].url == "https://example.com/law"
    assert result.retrieved_at.utcoffset().total_seconds() == 0


@pytest.mark.parametrize(
    "query,count,need_content",
    [
        ("", 1, False),
        ("x" * 201, 1, False),
        ("x", 0, False),
        ("x", 51, False),
        ("x", True, False),
        ("x", 1, "true"),
        (123, 1, False),
    ],
)
def test_search_validates_bounds(query, count, need_content):
    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekSearchProvider(tool_settings(), transport=transport_json({})),
            "search",
            query,
            count=count,
            need_content=need_content,
        )
    assert error.value.status == 400


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"results": {}},
        {"results": ["bad"]},
        {"results": [{"title": "x"}]},
        {"results": [{"title": 3, "url": "https://example.com"}]},
    ],
)
def test_search_rejects_malformed_output(payload):
    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekSearchProvider(tool_settings(), transport=transport_json(payload)),
            "search",
            "法规",
        )
    assert error.value.status == 502


def test_fetch_validates_dns_and_uses_own_key():
    def handler(request):
        assert str(request.url) == "https://fetch.example/v1/tools/fetch"
        assert request.headers["Authorization"] == "Bearer fetch-only-secret"
        assert json.loads(request.content) == {"url": "https://example.com/law?q=2026"}
        return httpx.Response(200, json={"title": "法规", "content": "公开内容"})

    result = call(
        DeepSeekFetchProvider(
            tool_settings(), transport=httpx.MockTransport(handler), resolver=public_dns
        ),
        "fetch",
        "https://example.com/law?q=2026",
    )
    assert result.title == "法规"
    assert result.url == "https://example.com/law?q=2026"
    assert result.retrieved_at.tzinfo is not None


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "ftp://example.com/a",
        "http://localhost/a",
        "http://localhost./a",
        "http://a.localhost/a",
        "http://127.0.0.1/",
        "http://[::1]/",
        "http://[::ffff:127.0.0.1]/",
        "http://10.0.0.1/",
        "http://169.254.169.254/latest/meta-data",
        "http://100.100.100.200/",
        "http://metadata.google.internal/",
        "http://user:password@example.com/",
        "http://example.com/#fragment",
        "http://example.com:99999/",
        "https://example.com/\nfoo",
        "http://2130706433/",
        "http://0x7f000001/",
    ],
)
def test_fetch_rejects_unsafe_urls(url):
    async def resolver(host):
        return ["127.0.0.1"]

    def unexpected(request):
        pytest.fail("危险URL不得外发")

    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(
                tool_settings(), transport=httpx.MockTransport(unexpected), resolver=resolver
            ),
            "fetch",
            url,
        )
    assert error.value.status == 400


@pytest.mark.parametrize(
    "addresses",
    [["127.0.0.1"], ["93.184.216.34", "10.0.0.1"], ["::1"], [], ["invalid"], ["169.254.169.254"]],
)
def test_fetch_rejects_private_or_invalid_dns_answers(addresses):
    async def resolver(host):
        return addresses

    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(tool_settings(), transport=transport_json({}), resolver=resolver),
            "fetch",
            "https://public.example/a",
        )
    assert error.value.status == 400


@pytest.mark.parametrize(
    "payload", [{}, {"title": "x"}, {"title": 1, "content": "x"}, {"title": "x", "content": " "}]
)
def test_fetch_rejects_malformed_output(payload):
    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(
                tool_settings(), transport=transport_json(payload), resolver=public_dns
            ),
            "fetch",
            "https://example.com",
        )
    assert error.value.status == 502


@pytest.mark.parametrize(
    "provider,prefix,method,args",
    [
        (ArkOCRProvider, "ocr", "recognize", (PNG,)),
        (ArkEmbeddingProvider, "embedding", "embed", ("文本",)),
        (DeepSeekSearchProvider, "search", "search", ("法规",)),
        (DeepSeekFetchProvider, "fetch", "fetch", ("https://example.com",)),
    ],
)
def test_missing_capability_key_never_falls_back(provider, prefix, method, args):
    field = f"{prefix}_api_key"
    with pytest.raises(ServiceError) as error:
        instance = provider(tool_settings(**{field: None}), transport=transport_json({}))
        call(instance, method, *args)
    assert error.value.status == 503
    assert "secret" not in error.value.message


@pytest.mark.parametrize(
    "status,marker",
    [
        (401, "认证"),
        (403, "权限"),
        (402, "配额"),
        (429, "限流"),
        (500, "服务"),
        (503, "服务"),
        (302, "重定向"),
        (400, "请求"),
    ],
)
def test_http_errors_are_distinct_redacted_and_not_retried(status, marker):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(
            status,
            text="ocr-only-secret https://sensitive.example?key=secret",
            headers={"Location": "http://127.0.0.1"},
        )

    with pytest.raises(ServiceError) as error:
        call(
            ArkOCRProvider(tool_settings(), transport=httpx.MockTransport(handler)),
            "recognize",
            PNG,
        )
    assert len(requests) == 1
    assert error.value.status in {502, 503}
    assert marker in error.value.message
    assert "secret" not in str(error.value)
    assert "sensitive" not in str(error.value)
    assert error.value.__cause__ is None


def test_timeout_is_redacted_without_retry():
    requests = []

    def handler(request):
        requests.append(request)
        raise httpx.ReadTimeout("ocr-only-secret", request=request)

    with pytest.raises(ServiceError) as error:
        call(
            ArkOCRProvider(tool_settings(), transport=httpx.MockTransport(handler)),
            "recognize",
            PNG,
        )
    assert error.value.status == 504
    assert "超时" in error.value.message
    assert "secret" not in str(error.value)
    assert len(requests) == 1


def test_network_error_and_invalid_json_are_safe():
    def failure(request):
        raise httpx.ConnectError("ocr-only-secret", request=request)

    for transport in (
        httpx.MockTransport(failure),
        httpx.MockTransport(lambda request: httpx.Response(200, text="ocr-only-secret")),
    ):
        with pytest.raises(ServiceError) as error:
            call(ArkOCRProvider(tool_settings(), transport=transport), "recognize", PNG)
        assert error.value.status == 502
        assert "secret" not in str(error.value)


@pytest.mark.parametrize(
    "provider",
    [ArkOCRProvider, ArkEmbeddingProvider, DeepSeekSearchProvider, DeepSeekFetchProvider],
)
def test_all_clients_disable_proxy_redirects_and_close(provider, monkeypatch):
    monkeypatch.setenv("HTTPS_PROXY", "http://127.0.0.1:1")
    instance = provider(tool_settings(), transport=transport_json({}))
    assert instance._client._trust_env is False
    assert instance._client.follow_redirects is False
    asyncio.run(instance.aclose())
    assert instance._client.is_closed


@pytest.mark.parametrize(
    "provider,missing",
    [
        (ArkOCRProvider, "ocr_api_base_url"),
        (ArkOCRProvider, "ocr_model"),
        (ArkEmbeddingProvider, "embedding_base_url"),
        (ArkEmbeddingProvider, "embedding_model"),
        (DeepSeekSearchProvider, "search_api_base_url"),
        (DeepSeekFetchProvider, "fetch_api_base_url"),
    ],
)
def test_missing_address_or_model_is_unavailable(provider, missing):
    with pytest.raises(ServiceError) as error:
        provider(tool_settings(**{missing: None}), transport=transport_json({}))
    assert error.value.status == 503


def test_image_exact_size_limit_is_allowed():
    image = PNG + b"x" * (10 * 1024 * 1024 - len(PNG))
    payload = {"choices": [{"finish_reason": "stop", "message": {"content": "原文"}}]}
    assert (
        call(
            ArkOCRProvider(tool_settings(), transport=transport_json(payload)), "recognize", image
        ).text
        == "原文"
    )


def test_search_accepts_exact_bounds_and_empty_results():
    for count in (1, 50):
        result = call(
            DeepSeekSearchProvider(tool_settings(), transport=transport_json({"results": []})),
            "search",
            "x" * 200,
            count=count,
        )
        assert result.results == ()


@pytest.mark.parametrize("url", ["https://93.184.216.34/a", "https://[2606:4700:4700::1111]/a"])
def test_fetch_public_literal_needs_no_dns(url):
    async def unexpected(host):
        pytest.fail("IP字面量不应触发DNS")

    result = call(
        DeepSeekFetchProvider(
            tool_settings(),
            transport=transport_json({"title": "公开页", "content": "正文"}),
            resolver=unexpected,
        ),
        "fetch",
        url,
    )
    assert result.url == url


@pytest.mark.parametrize(
    "url",
    [
        "http://[fec0::1]/",
        "http://[64:ff9b::7f00:1]/",
        "http://[2002:7f00:1::]/",
        "http://224.0.0.1/",
        "https://example.com/%0afoo",
        "https://example.com\\@127.0.0.1/",
        "https://example.com:0/",
    ],
)
def test_additional_unsafe_url_forms_are_rejected_without_dns(url):
    async def unexpected(host):
        pytest.fail("危险URL不应解析或外发")

    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(
                tool_settings(), transport=transport_json({}), resolver=unexpected
            ),
            "fetch",
            url,
        )
    assert error.value.status == 400


@pytest.mark.parametrize("failure", [OSError("sensitive-host"), TimeoutError("sensitive-host")])
def test_dns_failure_is_safe_and_prevents_request(failure):
    async def resolver(host):
        raise failure

    def unexpected(request):
        pytest.fail("DNS失败不应调用上游")

    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(
                tool_settings(), transport=httpx.MockTransport(unexpected), resolver=resolver
            ),
            "fetch",
            "https://example.com",
        )
    assert error.value.status == 400
    assert "sensitive" not in str(error.value)


def test_default_resolver_checks_all_addresses_without_real_dns(monkeypatch):
    async def getaddrinfo(self, *args, **kwargs):
        return [(2, 1, 6, "", ("93.184.216.34", 0)), (2, 1, 6, "", ("127.0.0.1", 0))]

    monkeypatch.setattr(asyncio.BaseEventLoop, "getaddrinfo", getaddrinfo)
    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(tool_settings(), transport=transport_json({})),
            "fetch",
            "https://example.com",
        )
    assert error.value.status == 400


@pytest.mark.parametrize("field", ["url", "final_url"])
def test_fetch_rejects_unsafe_reported_final_url(field):
    payload = {"title": "公开页", "content": "正文", field: "http://127.0.0.1"}
    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(
                tool_settings(), transport=transport_json(payload), resolver=public_dns
            ),
            "fetch",
            "https://example.com",
        )
    assert error.value.status == 502


def test_fetch_never_follows_upstream_http_redirect():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(302, headers={"Location": "http://127.0.0.1"})

    with pytest.raises(ServiceError) as error:
        call(
            DeepSeekFetchProvider(
                tool_settings(), transport=httpx.MockTransport(handler), resolver=public_dns
            ),
            "fetch",
            "https://example.com",
        )
    assert error.value.status == 502
    assert len(requests) == 1


@pytest.mark.parametrize(
    "payload",
    [
        {"title": "文件", "content": "二进制伪装正文", "content_type": "application/pdf"},
        {"title": "文件", "content": "二进制伪装正文", "mime_type": "image/png"},
        {"title": "文件", "content": "%PDF-1.7 synthetic"},
        {"title": "文件", "content": "text\u0000binary"},
        {"title": "文件", "content": "PK\u0003\u0004zip"},
    ],
)
def test_fetch_rejects_reported_binary_or_nontext_content(payload):
    with pytest.raises(ServiceError):
        call(
            DeepSeekFetchProvider(
                tool_settings(), transport=transport_json(payload), resolver=public_dns
            ),
            "fetch",
            "https://example.com/reference",
        )


def test_fetch_bounds_raw_response_before_json_decoding():
    with pytest.raises(ServiceError):
        call(
            DeepSeekFetchProvider(
                tool_settings(),
                transport=transport_json(
                    {
                        "title": "巨大网页",
                        "content": "x" * (1024 * 1024),
                    }
                ),
                resolver=public_dns,
            ),
            "fetch",
            "https://example.com/reference",
        )


def test_fetch_refuses_binary_http_content_type():
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            content=b'{"title":"file","content":"binary"}',
            headers={"Content-Type": "application/octet-stream"},
        )
    )
    with pytest.raises(ServiceError):
        call(
            DeepSeekFetchProvider(tool_settings(), transport=transport, resolver=public_dns),
            "fetch",
            "https://example.com/reference",
        )


def test_search_removes_page_fragment_without_rejecting_other_hits():
    payload = {
        "results": [
            {
                "title": "公告定位",
                "url": "https://example.com/notice#:~:text=考试时间",
                "snippet": "片段",
            },
            {"title": "其他来源", "url": "https://example.org/notice", "snippet": "资料"},
        ]
    }
    result = call(
        DeepSeekSearchProvider(tool_settings(), transport=transport_json(payload)),
        "search",
        "考试公告",
        count=3,
        need_content=True,
    )
    assert len(result.results) == 2
    assert result.results[0].url == "https://example.com/notice"
    assert result.results[1].url == "https://example.org/notice"


@pytest.mark.parametrize("url", ["http://127.0.0.1/private#section", "file:///etc/passwd#section"])
def test_search_fragment_removal_never_allows_unsafe_targets(url):
    with pytest.raises(ServiceError):
        call(
            DeepSeekSearchProvider(
                tool_settings(),
                transport=transport_json(
                    {
                        "results": [{"title": "不安全目标", "url": url}],
                    }
                ),
            ),
            "search",
            "公告",
        )
