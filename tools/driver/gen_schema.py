"""Generates driver/protocol.schema.json from the operation table of driver/PROTOCOL.md section 5.

    python tools/driver/gen_schema.py           write driver/protocol.schema.json
    python tools/driver/gen_schema.py --check   exit 1 if it is not what this script writes

Then check it: node tools/driver/check_schema.js. tools/definitions/check.py runs both.
"""
import json
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "driver" / "protocol.schema.json"

R = lambda name: {"$ref": f"#/$defs/{name}"}
STR = {"type": "string"}
BOOL = {"type": "boolean"}
OBJ = {"type": "object"}

# op -> (args {name: (schema, required)}, result schema)
OPS = {
    "configure": ({"auth": (R("auth"), True), "userAgent": (STR, False),
                   "timeoutSeconds": ({"type": "integer", "minimum": 1}, False)},
                  {"type": "object", "properties": {"agent": R("url")}}),
    "discover_storage": ({"url": (R("url"), True)}, R("storage")),
    "head": ({"url": (R("url"), True)}, R("metadata")),
    "read": ({"url": (R("url"), True), "accept": (STR, False), "ifNoneMatch": (STR, False),
              "rangeStart": (R("count"), False), "rangeEnd": (R("count"), False)},
             {"type": "object", "required": ["metadata", "notModified"],
              "properties": {"metadata": R("metadata"), "notModified": BOOL, "body": R("responseBody")}}),
    "list_container": ({"url": (R("url"), True), "limit": (R("count"), False)}, R("itemsResult")),
    "create": ({"container": (R("url"), True), "body": (R("requestBody"), False), "contentType": (STR, False),
                "slug": (STR, False), "types": (R("urls"), False), "links": (R("links"), False)}, R("created")),
    "create_container": ({"parent": (R("url"), True), "slug": (STR, False)}, R("created")),
    "update": ({"url": (R("url"), True), "body": (R("requestBody"), True), "contentType": (STR, False),
                "ifMatch": (STR, False), "ifNoneMatch": (STR, False), "links": (R("links"), False)}, R("update")),
    "patch": ({"url": (R("url"), True), "patch": (R("jsonPatch"), True), "ifMatch": (STR, False)}, R("update")),
    "delete": ({"url": (R("url"), True), "recursive": (BOOL, False), "ifMatch": (STR, False)}, R("empty")),
    "read_linkset": ({"url": (R("url"), True)},
                     {"type": "object", "required": ["url", "linkset", "allow", "acceptPatch"],
                      "properties": {"url": R("url"), "etag": STR, "linkset": R("linksetDocument"),
                                     "allow": R("strings"), "acceptPatch": R("strings")}}),
    "update_linkset": ({"linksetUrl": (R("url"), True), "linkset": (R("linksetDocument"), True),
                        "ifMatch": (STR, False)}, R("update")),
    "patch_linkset": ({"linksetUrl": (R("url"), True), "patch": (R("jsonPatch"), True),
                       "ifMatch": (STR, False)}, R("update")),
    "move": ({"url": (R("url"), True), "destination": (R("url"), True), "ifMatch": (STR, False)}, R("moved")),
    "request_access": ({"serviceUrl": (R("url"), True), "request": (OBJ, True)}, R("locationResult")),
    "get_access_request": ({"url": (R("url"), True)}, R("documentResult")),
    "list_access_requests": ({"serviceUrl": (R("url"), True), "limit": (R("count"), False)}, R("itemsResult")),
    "cancel_access_request": ({"url": (R("url"), True)}, R("empty")),
    "grant_access": ({"serviceUrl": (R("url"), True), "grant": (OBJ, True)}, R("locationResult")),
    "get_access_grant": ({"url": (R("url"), True)}, R("documentResult")),
    "list_access_grants": ({"serviceUrl": (R("url"), True), "limit": (R("count"), False)}, R("itemsResult")),
    "revoke_access_grant": ({"url": (R("url"), True)}, R("empty")),
    "subscribe": ({"serviceUrl": (R("url"), True), "topics": (R("urls"), True), "inbox": (R("url"), True),
                   "expires": ({"type": "string", "format": "date-time"}, False)}, R("subscription")),
    "list_subscriptions": ({"serviceUrl": (R("url"), True), "limit": (R("count"), False)}, R("itemsResult")),
    "get_subscription": ({"url": (R("url"), True)}, R("subscription")),
    "unsubscribe": ({"url": (R("url"), True)}, R("empty")),
    "verify_notification": ({"method": (STR, True), "url": (R("url"), True), "headers": (R("headerFields"), True),
                             "bodyBase64": (R("base64"), True),
                             "trustedStorages": ({"type": "array", "minItems": 1, "items": R("url")}, False)},
                            {"type": "object", "required": ["storage", "keyid", "activities"],
                             "properties": {"storage": R("url"), "keyid": STR,
                                            "activities": {"type": "array", "items": R("activity")}}}),
    "list_types": ({"serviceUrl": (R("url"), True), "limit": (R("count"), False)},
                   {"type": "object", "required": ["types", "truncated"],
                    "properties": {"types": {"type": "array"}, "truncated": BOOL}}),
    "search": ({"serviceUrl": (R("url"), True), "query": (R("typeQuery"), True), "limit": (R("count"), False)},
               R("itemsResult")),
    "shutdown": ({}, R("empty")),
}


def optional(schema):
    return {"anyOf": [schema, {"type": "null"}]}


def args_schema(args):
    return {"type": "object", "additionalProperties": False,
            "required": [n for n, (_, req) in args.items() if req],
            "properties": {n: (s if req else optional(s)) for n, (s, req) in args.items()}}


defs = {
    "url": {"type": "string", "pattern": "^[A-Za-z][A-Za-z0-9+.-]*:[^\\s]*$",
            "description": "An absolute URL (PROTOCOL.md section 4)."},
    "urls": {"type": "array", "items": R("url")},
    "strings": {"type": "array", "items": STR},
    "count": {"type": "integer", "minimum": 0},
    "base64": {"type": "string", "pattern": "^[A-Za-z0-9+/]*={0,2}$"},
    "operationName": {"type": "string", "pattern": "^[a-z][a-z_]*$",
                      "description": "An operation name. A name this version does not define is allowed in a hello: the driver ignores it (section 8)."},
    "operation": {"enum": sorted(OPS)},
    "hello": {
        "type": "object", "additionalProperties": False, "required": ["hello"],
        "properties": {"hello": {
            "type": "object", "additionalProperties": False,
            "required": ["protocol", "client", "auth", "operations"],
            "properties": {
                "protocol": {"const": "touchstone-driver/1"},
                "client": {"type": "object", "additionalProperties": False, "required": ["name"],
                           "properties": {"name": {"type": "string", "minLength": 1}, "version": STR,
                                          "homepage": R("url")}},
                "language": STR,
                "auth": {"type": "array", "uniqueItems": True, "items": {"enum": ["none", "bearer", "cid", "openid", "saml"]}},
                "operations": {"type": "array", "uniqueItems": True, "items": R("operationName")},
            }}},
    },
    "request": {
        "type": "object", "additionalProperties": False, "required": ["id", "op", "args"],
        "properties": {"id": {"type": "integer", "minimum": 1}, "op": R("operation"), "args": OBJ},
        "allOf": [{"if": {"type": "object", "required": ["op"], "properties": {"op": {"const": op}}},
                   "then": {"type": "object", "properties": {"args": R(f"args-{op}")}}} for op in OPS],
    },
    "response": {"oneOf": [
        {"type": "object", "additionalProperties": False, "required": ["id", "ok", "result"],
         "properties": {"id": {"type": "integer", "minimum": 1}, "ok": {"const": True}, "result": OBJ}},
        {"type": "object", "additionalProperties": False, "required": ["id", "ok", "error"],
         "properties": {"id": {"type": ["integer", "null"], "minimum": 1}, "ok": {"const": False},
                        "error": R("error")}},
    ]},
    "error": {
        "type": "object", "required": ["kind", "message"],
        "properties": {"kind": {"enum": ["http", "authentication", "protocol", "verification", "transport",
                                         "invalidArguments", "unsupported", "internal"]},
                       "message": STR, "status": {"type": "integer", "minimum": 100, "maximum": 599},
                       "problem": OBJ},
        "if": {"type": "object", "required": ["kind"], "properties": {"kind": {"const": "http"}}},
        "then": {"type": "object", "required": ["status"], "properties": {"status": True}},
    },
    "auth": {"oneOf": [
        {"type": "object", "additionalProperties": False, "required": ["type"],
         "properties": {"type": {"const": "none"}}},
        {"type": "object", "additionalProperties": False, "required": ["type", "token"],
         "properties": {"type": {"const": "bearer"}, "token": {"type": "string", "minLength": 1}}},
        {"type": "object", "additionalProperties": False, "required": ["type", "agent", "privateJwk"],
         "properties": {"type": {"const": "cid"}, "agent": R("url"),
                        "privateJwk": {"type": "object", "required": ["kty"], "properties": {"kty": STR}}, "kid": optional(STR)}},
        {"type": "object", "additionalProperties": False, "required": ["type", "idToken"],
         "properties": {"type": {"const": "openid"}, "idToken": {"type": "string", "minLength": 1}}},
        {"type": "object", "additionalProperties": False, "required": ["type", "assertion"],
         "properties": {"type": {"const": "saml"}, "assertion": {"type": "string", "pattern": "^[A-Za-z0-9_-]+=*$"}}},
    ]},
    "requestBody": {"oneOf": [
        {"type": "object", "additionalProperties": False, "required": ["text"], "properties": {"text": STR}},
        {"type": "object", "additionalProperties": False, "required": ["base64"], "properties": {"base64": R("base64")}},
        {"type": "object", "additionalProperties": False, "required": ["json"], "properties": {"json": {}}},
    ]},
    "responseBody": {"oneOf": [
        {"type": "object", "additionalProperties": False, "required": ["text"], "properties": {"text": STR}},
        {"type": "object", "additionalProperties": False, "required": ["base64"], "properties": {"base64": R("base64")}},
    ]},
    "links": {"type": "array", "items": {"type": "object", "additionalProperties": False, "required": ["href", "rel"],
                                         "properties": {"href": R("url"), "rel": {"type": "string", "minLength": 1}}}},
    "jsonPatch": {"type": "array", "items": {
        "type": "object", "required": ["op", "path"],
        "properties": {"op": {"enum": ["add", "remove", "replace", "move", "copy", "test"]}, "path": STR, "from": STR}}},
    "typeQuery": {"type": "object", "additionalProperties": {
        "type": "array", "items": {"anyOf": [R("url"), {"type": "array", "minItems": 1, "items": R("url")}]}}},
    "linksetDocument": {"type": "object", "required": ["linkset"], "properties": {"linkset": {"type": "array"}}},
    "headerFields": {"type": "object", "additionalProperties": {"type": "array", "items": STR}},
    "link": {"type": "object", "required": ["href", "rel"],
             "properties": {"href": R("url"), "rel": STR, "params": {"type": "object", "additionalProperties": STR}}},
    "metadata": {"type": "object", "required": ["url", "status"],
                 "properties": {"url": R("url"), "status": {"type": "integer", "minimum": 100, "maximum": 599},
                                "etag": STR, "lastModified": STR, "contentType": STR, "contentLength": R("count"),
                                "links": {"type": "array", "items": R("link")}, "types": R("strings"),
                                "allow": R("strings"), "acceptPatch": R("strings")}},
    "item": {"type": "object", "required": ["id"], "properties": {"id": R("url"), "types": R("strings")}},
    "itemsResult": {"type": "object", "required": ["items", "truncated"],
                    "properties": {"items": {"type": "array", "items": R("item")}, "truncated": BOOL}},
    "created": {"type": "object", "required": ["location"], "properties": {"location": R("url"), "metadata": R("metadata")}},
    "update": {"type": "object", "required": ["status"],
               "properties": {"status": {"type": "integer", "minimum": 200, "maximum": 299}, "etag": STR,
                              "metadata": R("metadata")}},
    "moved": {"type": "object", "required": ["status", "location"],
              "properties": {"status": {"type": "integer", "minimum": 200, "maximum": 299}, "location": R("url"),
                             "metadata": R("metadata")}},
    "storage": {"type": "object", "required": ["id", "services"],
                "properties": {"id": R("url"), "storageRoot": R("url"), "raw": OBJ,
                               "services": {"type": "array", "items": {
                                   "type": "object", "required": ["types", "serviceEndpoint"],
                                   "properties": {"id": STR, "types": R("strings"), "serviceEndpoint": R("url")}}}}},
    "subscription": {"type": "object", "required": ["subscription"],
                     "properties": {"subscription": R("url"), "raw": OBJ}},
    "activity": {"type": "object", "required": ["types", "object"],
                 "properties": {"id": STR, "types": R("strings"), "object": R("url"), "objectTypes": R("strings")}},
    "locationResult": {"type": "object", "required": ["location"], "properties": {"location": R("url")}},
    "documentResult": {"type": "object", "required": ["document"], "properties": {"document": OBJ}},
    "empty": {"type": "object"},
}
for op, (args, result) in OPS.items():
    defs[f"args-{op}"] = args_schema(args)
    defs[f"result-{op}"] = result

schema = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": "https://example.org/touchstone/schema/driver/1",
    "title": "Touchstone client driver protocol, touchstone-driver/1",
    "description": "Validates one line of the protocol between Touchstone's client driver and an adapter (driver/PROTOCOL.md): a hello, a request or a response. A response carries no op, so its result is checked against $defs/result-<op> of the request with its id. Arguments are strict, since the driver writes them; results are open, since an adapter reports what its client exposes and /1 may gain optional members (section 8). PROPOSED on 2026-10-29 (DECISIONS.md D-0103); freezing it is Gate D, and after that any change needs a DECISIONS.md entry.",
    "oneOf": [R("hello"), R("request"), R("response")],
    "$defs": defs,
}


def compact(value, indent=0, width=110):
    """JSON in which every object or array that fits on one line stays on one line."""
    flat = json.dumps(value, ensure_ascii=False)
    if len(flat) + indent <= width or not isinstance(value, (dict, list)) or not value:
        return flat
    pad = " " * (indent + 2)
    if isinstance(value, dict):
        body = ",\n".join(f"{pad}{json.dumps(k)}: {compact(v, indent + 2, width)}" for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    body = ",\n".join(pad + compact(v, indent + 2, width) for v in value)
    return "[\n" + body + "\n" + " " * indent + "]"


text = compact(schema) + "\n"
if "--check" in sys.argv:
    current = OUT.read_text(encoding="utf-8") if OUT.exists() else None
    if current != text:
        print(f"{OUT.name} is not current: run python tools/driver/gen_schema.py")
        sys.exit(1)
    print(f"{OUT.name} is current")
else:
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
