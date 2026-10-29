"""The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3).

It writes the hello, reads one request a line from stdin, calls the method of ``LwsDriverAdapter`` for
its operation, and writes one response a line to stdout. stdout carries protocol lines only: log to
stderr. While ``serve`` runs on the real stdout, ``print`` goes to stderr too.

    def main() -> int:
        serve(MyAdapter())
        return 0

    if __name__ == "__main__":
        sys.exit(main())

Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.
"""

from __future__ import annotations

import asyncio
import inspect
import json
import sys
import traceback
from typing import Any, BinaryIO, Optional, Tuple

from lws_driver_adapter import OPERATIONS, AdapterError, JsonObject, LwsDriverAdapter

PROTOCOL = "touchstone-driver/1"

_OPERATIONS = frozenset(OPERATIONS)


def serve(
    adapter: LwsDriverAdapter,
    requests: Optional[BinaryIO] = None,
    responses: Optional[BinaryIO] = None,
) -> None:
    """Serves the driver until end of input or ``shutdown``; stdin and stdout unless streams are given."""
    if requests is None:
        requests = sys.stdin.buffer
    if responses is None:
        responses = sys.stdout.buffer
        # Whatever the adapter or its client prints goes to stderr, which the driver keeps as the log.
        sys.stdout = sys.stderr
    _Server(adapter, responses).serve(requests)


def hello(adapter: LwsDriverAdapter) -> JsonObject:
    """The hello: the client, its authentication, and every operation the adapter implements."""
    info = adapter.client_info()
    client: JsonObject = {"name": info.name}
    if info.version:
        client["version"] = info.version
    if info.homepage:
        client["homepage"] = info.homepage
    return {
        "hello": {
            "protocol": PROTOCOL,
            "client": client,
            "language": "python",
            "auth": list(dict.fromkeys(adapter.auth_kinds())),
            "operations": [op for op in OPERATIONS if _implemented(adapter, op)],
        }
    }


def _implemented(adapter: LwsDriverAdapter, method: str) -> bool:
    """Whether the adapter's class implements a method itself, rather than leaving LwsDriverAdapter's default."""
    return getattr(type(adapter), method, None) is not getattr(LwsDriverAdapter, method)


class _Server:
    def __init__(self, adapter: LwsDriverAdapter, responses: BinaryIO) -> None:
        self._adapter = adapter
        self._responses = responses
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    def serve(self, requests: BinaryIO) -> None:
        self._write(hello(self._adapter))
        try:
            for raw in iter(requests.readline, b""):
                line = raw.strip()
                if not line:
                    continue
                response, shutdown = self._respond(line)
                self._write(response)
                if shutdown:
                    return
        finally:
            if self._loop is not None:
                self._loop.close()

    def _respond(self, line: bytes) -> Tuple[JsonObject, bool]:
        """The response to one request line, and whether it was shutdown."""
        try:
            request = json.loads(line)
        except (ValueError, RecursionError):
            request = None
        if not isinstance(request, dict):
            return _failure(None, AdapterError.invalid_arguments("a request is a JSON object")), False
        request_id = request.get("id")
        op = request.get("op")
        if op == "shutdown":
            return {"id": request_id, "ok": True, "result": {}}, True
        try:
            if not isinstance(op, str) or op not in _OPERATIONS:
                raise AdapterError.unsupported(str(op))
            args = request.get("args")
            if args is None:
                args = {}
            if not isinstance(args, dict):
                raise AdapterError.invalid_arguments("args is a JSON object")
            result = getattr(self._adapter, op)(args)
            if inspect.isawaitable(result):
                if self._loop is None:
                    self._loop = asyncio.new_event_loop()
                result = self._loop.run_until_complete(result)
            if result is None:
                result = {}
            if not isinstance(result, dict):
                raise TypeError(f"{op} returned {type(result).__name__}, not a dict")
            return {"id": request_id, "ok": True, "result": _without_none(result)}, False
        except Exception as e:
            return _failure(request_id, e), False

    def _write(self, message: JsonObject) -> None:
        try:
            text = json.dumps(message, allow_nan=False, separators=(",", ":"))
        except (TypeError, ValueError, RecursionError) as e:
            # A result that cannot be serialized (a set, a NaN, a cycle) is a bug: report it.
            failure = _failure(message.get("id"), TypeError(f"the result is not JSON: {e}"))
            text = json.dumps(failure, separators=(",", ":"))
        self._responses.write(text.encode("utf-8") + b"\n")
        self._responses.flush()


def _without_none(value: Any) -> Any:
    """A result with its None members left out: the protocol reads null and absent alike (section 4),
    and the driver checks results against a schema that wants absent members left out."""
    if isinstance(value, dict):
        return {name: _without_none(member) for name, member in value.items() if member is not None}
    if isinstance(value, (list, tuple)):
        return [_without_none(member) for member in value]
    return value


def _failure(request_id: Any, e: BaseException) -> JsonObject:
    if isinstance(e, AdapterError):
        error: JsonObject = {"kind": e.kind}
        if e.status is not None:
            error["status"] = e.status
        error["message"] = e.message or e.kind
        return {"id": request_id, "ok": False, "error": error}
    # A bug in the adapter or the client: report it, and keep running.
    traceback.print_exception(type(e), e, e.__traceback__, file=sys.stderr)
    return {"id": request_id, "ok": False, "error": {"kind": "internal", "message": f"{type(e).__name__}: {e}"}}
