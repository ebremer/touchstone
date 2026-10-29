"""Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5).

One method per operation, named as the operation is. Each takes the operation's arguments and returns
its result, both as the JSON objects (dicts) the protocol describes, and raises an ``AdapterError``
for an outcome that is not a result, such as a 404. A method may be ``async def``: the runner runs it
on one event loop, kept for the adapter's life.

Implement the operations your client has, by calling your client's own operation with its own
options. Leave the others: they answer ``unsupported``, and the rules that need them stay untested,
never failed. ``adapter_runner`` lists the ones you implement in the hello.

Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The point is
to see what the client sends.

The result shapes of section 4.1, which the methods below name. An optional member may be left out
or ``None``: the runner leaves out ``None`` members, and the protocol reads both as absent.

- Metadata: ``{url, status, etag?, lastModified?, contentType?, contentLength?, links?, types?,
  allow?, acceptPatch?}``; ``etag`` verbatim, quotes and ``W/`` included; a link is
  ``{href, rel, params?}``. Report what your client exposes: only ``url`` and ``status`` are required.
- Item: ``{id, types?, ...}``, anything else the listing says added as received.
- Created: ``{location, metadata?}``.
- Update: ``{status, etag?, metadata?}``.
- Moved: ``{status, location, metadata?}``.
- Storage: ``{id, storageRoot?, services: [{id?, types, serviceEndpoint}], raw?}``.
- Subscription: ``{subscription, raw?}``.

A ``body`` argument is ``{text}``, ``{base64}`` or ``{json}``; a ``body`` in a result is ``{text}``
when the content type is textual and ``{base64}`` otherwise (section 4).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Awaitable, Collection, Dict, Optional, Union

JsonObject = Dict[str, Any]
"""A JSON object: the arguments of an operation, and its result."""

Result = Union[JsonObject, None, Awaitable[Optional[JsonObject]]]
"""What a method returns: its result (``None`` for ``{}``), or, from an ``async def``, an awaitable of it."""

OPERATIONS = (
    "configure",
    "discover_storage",
    "head",
    "read",
    "list_container",
    "create",
    "create_container",
    "update",
    "patch",
    "delete",
    "read_linkset",
    "update_linkset",
    "patch_linkset",
    "move",
    "request_access",
    "get_access_request",
    "list_access_requests",
    "cancel_access_request",
    "grant_access",
    "get_access_grant",
    "list_access_grants",
    "revoke_access_grant",
    "subscribe",
    "list_subscriptions",
    "get_subscription",
    "unsubscribe",
    "verify_notification",
    "list_types",
    "search",
)
"""Every operation of section 5 but shutdown. Each is the method of ``LwsDriverAdapter`` of its name."""

KINDS = frozenset(
    {
        "http",
        "authentication",
        "protocol",
        "verification",
        "transport",
        "invalidArguments",
        "unsupported",
        "internal",
    }
)
"""The error kinds of section 4.2."""


class AdapterError(Exception):
    """An operation's outcome that is not a result (section 4.2).

    It is reported to the driver, and the adapter keeps running. A 409 from deleting a non-empty
    container is exactly what a drive may be looking for.
    """

    def __init__(self, kind: str, message: str, status: Optional[int] = None) -> None:
        """
        :param kind: http, authentication, protocol, verification, transport, invalidArguments,
            unsupported or internal
        :param message: what happened
        :param status: the HTTP status of the response that caused it; required for http
        """
        if kind not in KINDS:
            raise ValueError(f"{kind!r} is not an error kind of touchstone-driver/1")
        if status is not None and not (
            isinstance(status, int) and not isinstance(status, bool) and 100 <= status <= 599
        ):
            raise ValueError(f"{status!r} is not an HTTP status")
        if kind == "http" and status is None:
            raise ValueError("an http error has a status")
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.status = status

    @classmethod
    def http(cls, status: int, message: str) -> AdapterError:
        """A response with a status the operation does not succeed with."""
        return cls("http", message, status)

    @classmethod
    def unsupported(cls, what: str) -> AdapterError:
        """The operation, or an argument it was given, is not available in this client."""
        return cls("unsupported", f"{what} is not available in this client")

    @classmethod
    def invalid_arguments(cls, message: str) -> AdapterError:
        """The request's arguments are missing or malformed."""
        return cls("invalidArguments", message)

    @classmethod
    def transport(cls, message: str) -> AdapterError:
        """No response: connection refused, TLS failure, timeout."""
        return cls("transport", message)


@dataclass(frozen=True)
class ClientInfo:
    """The client under test, as the hello names it (section 3).

    It becomes the subject of the session's EARL report.
    """

    name: str
    """The client's name."""
    version: Optional[str] = None
    """Its version."""
    homepage: Optional[str] = None
    """An absolute http(s) URL."""


class LwsDriverAdapter(ABC):
    """Subclass it, and implement client_info, auth_kinds, configure and the operations your client has."""

    @abstractmethod
    def client_info(self) -> ClientInfo:
        """The client under test, as the session's report names it."""

    @abstractmethod
    def auth_kinds(self) -> Collection[str]:
        """The kinds of ``configure`` authentication your client supports: none, bearer, cid, openid, saml."""

    @abstractmethod
    def configure(self, args: JsonObject) -> Result:
        """Section 5.1: ``auth, userAgent?, timeoutSeconds?`` → ``{agent?}``.

        Build the client every later operation uses, with ``auth``. Always implemented.
        """

    # ---- core ----

    def discover_storage(self, args: JsonObject) -> Result:
        """``url`` → Storage: find the storage a URL belongs to and read its description."""
        raise AdapterError.unsupported("discover_storage")

    def head(self, args: JsonObject) -> Result:
        """``url`` → Metadata."""
        raise AdapterError.unsupported("head")

    def read(self, args: JsonObject) -> Result:
        """``url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?`` → ``{metadata, notModified, body?}``."""
        raise AdapterError.unsupported("read")

    def list_container(self, args: JsonObject) -> Result:
        """``url, limit?`` → ``{items, truncated}``, following every page."""
        raise AdapterError.unsupported("list_container")

    def create(self, args: JsonObject) -> Result:
        """``container, body?, contentType?, slug?, types?, links?`` → Created."""
        raise AdapterError.unsupported("create")

    def create_container(self, args: JsonObject) -> Result:
        """``parent, slug?`` → Created."""
        raise AdapterError.unsupported("create_container")

    def update(self, args: JsonObject) -> Result:
        """``url, body, contentType?, ifMatch?, ifNoneMatch?, links?`` → Update."""
        raise AdapterError.unsupported("update")

    def patch(self, args: JsonObject) -> Result:
        """``url, patch, ifMatch?`` → Update."""
        raise AdapterError.unsupported("patch")

    def delete(self, args: JsonObject) -> Result:
        """``url, recursive?, ifMatch?`` → ``{}``."""
        raise AdapterError.unsupported("delete")

    def read_linkset(self, args: JsonObject) -> Result:
        """``url`` (the resource) → ``{url, etag?, linkset, allow, acceptPatch}``."""
        raise AdapterError.unsupported("read_linkset")

    def update_linkset(self, args: JsonObject) -> Result:
        """``linksetUrl, linkset, ifMatch?`` → Update."""
        raise AdapterError.unsupported("update_linkset")

    def patch_linkset(self, args: JsonObject) -> Result:
        """``linksetUrl, patch, ifMatch?`` → Update."""
        raise AdapterError.unsupported("patch_linkset")

    def move(self, args: JsonObject) -> Result:
        """``url, destination, ifMatch?`` → Moved."""
        raise AdapterError.unsupported("move")

    def request_access(self, args: JsonObject) -> Result:
        """``serviceUrl, request`` → ``{location}``."""
        raise AdapterError.unsupported("request_access")

    def get_access_request(self, args: JsonObject) -> Result:
        """``url`` → ``{document}``."""
        raise AdapterError.unsupported("get_access_request")

    def list_access_requests(self, args: JsonObject) -> Result:
        """``serviceUrl, limit?`` → ``{items, truncated}``."""
        raise AdapterError.unsupported("list_access_requests")

    def cancel_access_request(self, args: JsonObject) -> Result:
        """``url`` → ``{}``."""
        raise AdapterError.unsupported("cancel_access_request")

    def grant_access(self, args: JsonObject) -> Result:
        """``serviceUrl, grant`` → ``{location}``."""
        raise AdapterError.unsupported("grant_access")

    def get_access_grant(self, args: JsonObject) -> Result:
        """``url`` → ``{document}``."""
        raise AdapterError.unsupported("get_access_grant")

    def list_access_grants(self, args: JsonObject) -> Result:
        """``serviceUrl, limit?`` → ``{items, truncated}``."""
        raise AdapterError.unsupported("list_access_grants")

    def revoke_access_grant(self, args: JsonObject) -> Result:
        """``url`` → ``{}``."""
        raise AdapterError.unsupported("revoke_access_grant")

    # ---- notifications ----

    def subscribe(self, args: JsonObject) -> Result:
        """``serviceUrl, topics, inbox, expires?`` → Subscription."""
        raise AdapterError.unsupported("subscribe")

    def list_subscriptions(self, args: JsonObject) -> Result:
        """``serviceUrl, limit?`` → ``{items, truncated}``."""
        raise AdapterError.unsupported("list_subscriptions")

    def get_subscription(self, args: JsonObject) -> Result:
        """``url`` → Subscription."""
        raise AdapterError.unsupported("get_subscription")

    def unsubscribe(self, args: JsonObject) -> Result:
        """``url`` → ``{}``."""
        raise AdapterError.unsupported("unsubscribe")

    def verify_notification(self, args: JsonObject) -> Result:
        """Section 5.2: ``method, url, headers, bodyBase64, trustedStorages?`` → ``{storage, keyid, activities}``.

        Verify the delivery the way your client does, or raise a ``verification`` error. An activity
        is ``{id?, types, object, objectTypes}``.
        """
        raise AdapterError.unsupported("verify_notification")

    # ---- index ----

    def list_types(self, args: JsonObject) -> Result:
        """``serviceUrl, limit?`` → ``{types, truncated}``."""
        raise AdapterError.unsupported("list_types")

    def search(self, args: JsonObject) -> Result:
        """``serviceUrl, query, limit?`` → ``{items, truncated}``, following every page."""
        raise AdapterError.unsupported("search")
