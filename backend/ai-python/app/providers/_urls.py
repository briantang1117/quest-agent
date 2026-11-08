import asyncio
import ipaddress
import re
import socket
from collections.abc import Awaitable, Callable, Sequence
from urllib.parse import urlsplit

from app.core.errors import ServiceError

Resolver = Callable[[str], Awaitable[Sequence[str]]]


def _public_ip(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return False
    if isinstance(address, ipaddress.IPv6Address):
        # 拒绝IPv4映射和过渡地址，避免不同解析器对目的地址解释不一致。
        if (
            address.ipv4_mapped
            or address.sixtofour
            or address.teredo
            or address not in ipaddress.IPv6Network("2000::/3")
        ):
            return False
    return address.is_global and not address.is_multicast


def public_url_host(url: str) -> str:
    """先做无网络URL校验；解析异常只返回固定安全消息。"""
    invalid = ServiceError(400, 40002, "网页URL必须指向HTTP(S)公网，不能含凭据或片段")
    if (
        not isinstance(url, str)
        or not url
        or "\\" in url
        or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in url)
        or re.search(r"%(?:0[0-9a-f]|1[0-9a-f]|7f)", url, re.IGNORECASE)
    ):
        raise invalid
    try:
        parts = urlsplit(url)
        host = parts.hostname
        if (
            parts.scheme not in {"http", "https"}
            or not host
            or "@" in parts.netloc
            or parts.fragment
            or "#" in url
            or parts.port == 0
            or "%" in host
        ):
            raise invalid
        host = host.encode("idna").decode("ascii").lower().rstrip(".")
    except (ValueError, UnicodeError):
        raise invalid from None
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if (
            "." not in host
            or host in {"metadata.google.internal", "metadata.goog", "instance-data.ec2.internal"}
            or host.endswith((".localhost", ".local", ".internal", ".home", ".lan"))
            or host == "localhost"
            or len(host) > 253
            or any(
                not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
                for label in host.split(".")
            )
        ):
            raise invalid from None
    else:
        if not _public_ip(host):
            raise invalid
    return host


async def resolve_public_addresses(host: str) -> Sequence[str]:
    infos = await asyncio.get_running_loop().getaddrinfo(
        host, None, family=socket.AF_UNSPEC, type=socket.SOCK_STREAM
    )
    return [info[4][0] for info in infos]


async def validate_public_url(url: str, resolver: Resolver, resolution_timeout: float) -> None:
    host = public_url_host(url)
    try:
        ipaddress.ip_address(host)
    except ValueError:
        try:
            addresses = await asyncio.wait_for(resolver(host), timeout=resolution_timeout)
        except (OSError, ValueError, TimeoutError):
            raise ServiceError(400, 40002, "网页域名无法安全解析") from None
        if (
            not isinstance(addresses, (list, tuple))
            or not addresses
            or any(not isinstance(value, str) or not _public_ip(value) for value in addresses)
        ):
            raise ServiceError(400, 40002, "网页域名包含非公网地址，已拒绝抓取") from None
