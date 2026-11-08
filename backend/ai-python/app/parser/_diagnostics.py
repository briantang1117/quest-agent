"""预检作用域内将pypdf诊断改为固定消息，不更改其他线程/应用日志。"""

import logging
from contextlib import contextmanager
from contextvars import ContextVar
from threading import RLock

_current: ContextVar[list[bool] | None] = ContextVar("pdf_diagnostics", default=None)
_lock = RLock()
_users = 0
_previous_factory = None


def _record_factory(*args, **kwargs):
    record = _previous_factory(*args, **kwargs)
    state = _current.get()
    if state is not None and (record.name == "pypdf" or record.name.startswith("pypdf.")):
        state[0] = True
        record.msg = "PDF解析器产生诊断，需人工复核"
        record.args = ()
        record.exc_info = None
        record.exc_text = None
        record.stack_info = None
    return record


@contextmanager
def parser_diagnostics():
    global _users, _previous_factory
    state = [False]
    with _lock:
        if not _users:
            _previous_factory = logging.getLogRecordFactory()
            logging.setLogRecordFactory(_record_factory)
        _users += 1
    token = _current.set(state)
    try:
        yield state
    finally:
        _current.reset(token)
        with _lock:
            _users -= 1
            if not _users and logging.getLogRecordFactory() is _record_factory:
                logging.setLogRecordFactory(_previous_factory)
