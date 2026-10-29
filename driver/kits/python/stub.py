"""The starting point: an adapter that implements only ``configure``.

Every other operation answers ``unsupported``. It passes ``touchstone driver check -- python stub.py``
as it is. Replace the client name, keep your client in an attribute that ``configure`` builds, and
implement the operations your client has, one by one.
"""

from __future__ import annotations

import sys
from typing import Collection

from adapter_runner import serve
from lws_driver_adapter import AdapterError, ClientInfo, JsonObject, LwsDriverAdapter


class StubAdapter(LwsDriverAdapter):
    def client_info(self) -> ClientInfo:
        return ClientInfo(name="My LWS client (Python)", version="0.0.0")

    def auth_kinds(self) -> Collection[str]:
        return {"none"}

    def configure(self, args: JsonObject) -> JsonObject:
        kind = (args.get("auth") or {}).get("type")
        if kind not in self.auth_kinds():
            raise AdapterError.unsupported(f"authentication {kind}")
        # Build your client here, with the authentication args["auth"] describes.
        return {}


def main() -> int:
    serve(StubAdapter())
    return 0


if __name__ == "__main__":
    sys.exit(main())
