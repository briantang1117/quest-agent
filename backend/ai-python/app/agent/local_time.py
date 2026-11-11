"""只读本地时间工具：时钟可注入，返回值来自服务器而非模型猜测。"""

from datetime import UTC, datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator


class TimeArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    timezone: str = Field(default="Asia/Shanghai", min_length=1, max_length=64)

    @field_validator("timezone")
    @classmethod
    def known_timezone(cls, value):
        try:
            ZoneInfo(value)
        except (ValueError, ZoneInfoNotFoundError) as exc:
            raise ValueError("不支持的IANA时区") from exc
        return value


TIME_TOOL = {
    "type": "function",
    "function": {
        "name": "get_current_time",
        "description": (
            "读取服务器当前时间。理解今年、明年、今天等相对时间前先调用；默认Asia/Shanghai。"
        ),
        "parameters": TimeArguments.model_json_schema(),
    },
}


def current_time(arguments: TimeArguments, clock=None):
    instant = clock() if clock is not None else datetime.now(UTC)
    if instant.tzinfo is None or instant.utcoffset() is None:
        raise ValueError("时钟必须提供带时区的时间")
    local = instant.astimezone(ZoneInfo(arguments.timezone))
    offset = local.strftime("%z")
    return {
        "datetime": local.isoformat(timespec="seconds"),
        "date": local.date().isoformat(),
        "year": local.year,
        "timezone": arguments.timezone,
        "utc_offset": offset[:3] + ":" + offset[3:],
        "weekday": local.isoweekday(),
        "unix_timestamp": int(instant.timestamp()),
        "truncated": False,
    }
