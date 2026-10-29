//! The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3).

use std::any::Any;
use std::io::{self, BufRead, Write};
use std::panic::{self, AssertUnwindSafe};
use std::process::ExitCode;

use serde_json::{Map, Value, json};

use crate::adapter::LwsDriverAdapter;
use crate::error::{AdapterError, ErrorKind};

/// The protocol this kit speaks.
pub const PROTOCOL: &str = "touchstone-driver/1";

/// The kinds of `configure` authentication the protocol defines (section 5.1).
pub const AUTH_KINDS: [&str; 5] = ["none", "bearer", "cid", "openid", "saml"];

/// Every operation of section 5 but `shutdown`, in its order.
pub const OPERATIONS: [&str; 29] = [
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
];

/// Serves the driver on stdin and stdout until end of input or `shutdown`, and returns the exit
/// status: success then, failure when stdout breaks. Return it from `main`.
pub fn run<A: LwsDriverAdapter>(mut adapter: A) -> ExitCode {
    // stdout is locked per line, never for the whole run, so a thread of your client that writes to
    // it cannot hang the adapter. It should write to stderr all the same.
    match serve(&mut adapter, io::stdin().lock(), io::stdout()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("lws-driver-adapter: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Serves the driver on these streams until end of input or `shutdown`: writes the hello, reads one
/// request a line, calls the method of [`LwsDriverAdapter`] for its operation, and writes one
/// response a line, flushing each. Nothing else is written to `output`.
pub fn serve<A: LwsDriverAdapter + ?Sized>(
    adapter: &mut A,
    mut input: impl BufRead,
    mut output: impl Write,
) -> io::Result<()> {
    let operations = listed(adapter);
    write(&mut output, &hello(adapter, &operations))?;
    let mut line = Vec::new();
    loop {
        line.clear();
        if input.read_until(b'\n', &mut line)? == 0 {
            return Ok(());
        }
        let (response, last) = match std::str::from_utf8(&line) {
            Ok(text) if text.trim().is_empty() => continue,
            Ok(text) => respond(adapter, &operations, text),
            Err(_) => (not_a_request(), false),
        };
        write(&mut output, &response)?;
        if last {
            return Ok(());
        }
    }
}

/// The operations the hello lists: `configure`, then each one the adapter names, in the
/// protocol's order. A name the protocol does not define is left out, with a warning.
fn listed<A: LwsDriverAdapter + ?Sized>(adapter: &A) -> Vec<&'static str> {
    let named = adapter.operations();
    for name in named {
        if !OPERATIONS.contains(name) && *name != "shutdown" {
            eprintln!(
                "lws-driver-adapter: {name:?} is not an operation of {PROTOCOL}: left out of the hello"
            );
        }
    }
    OPERATIONS
        .into_iter()
        .filter(|op| *op == "configure" || named.contains(op))
        .collect()
}

/// The hello: the client, its authentication, and every operation the adapter implements.
fn hello<A: LwsDriverAdapter + ?Sized>(adapter: &A, operations: &[&str]) -> Value {
    let info = adapter.client();
    let mut client = Map::new();
    client.insert("name".into(), info.name.into());
    if let Some(version) = info.version {
        client.insert("version".into(), version.into());
    }
    if let Some(homepage) = info.homepage {
        client.insert("homepage".into(), homepage.into());
    }
    let mut auth: Vec<&str> = Vec::new();
    for kind in adapter.auth() {
        if !AUTH_KINDS.contains(kind) {
            eprintln!(
                "lws-driver-adapter: {kind:?} is not an authentication kind of {PROTOCOL}: left out of the hello"
            );
        } else if !auth.contains(kind) {
            auth.push(kind);
        }
    }
    json!({"hello": {
        "protocol": PROTOCOL,
        "client": client,
        "language": "rust",
        "auth": auth,
        "operations": operations,
    }})
}

/// The response to one request line, and whether it was the last.
fn respond<A: LwsDriverAdapter + ?Sized>(
    adapter: &mut A,
    operations: &[&str],
    line: &str,
) -> (Value, bool) {
    let Ok(Value::Object(request)) = serde_json::from_str::<Value>(line) else {
        return (not_a_request(), false);
    };
    let id = match request.get("id") {
        Some(id) if id.as_u64().is_some_and(|n| n >= 1) => id.clone(),
        _ => {
            let error = AdapterError::invalid_arguments("a request has a positive integer id");
            return (failure(Value::Null, &error), false);
        }
    };
    let op = request
        .get("op")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if op == "shutdown" {
        return (json!({"id": id, "ok": true, "result": {}}), true);
    }
    let outcome = if !OPERATIONS.contains(&op) {
        let given = request
            .get("op")
            .map_or_else(|| "null".to_owned(), Value::to_string);
        Err(AdapterError::new(
            ErrorKind::Unsupported,
            None,
            format!("{given} is not an operation of {PROTOCOL}"),
        ))
    } else if !operations.contains(&op) {
        Err(AdapterError::unsupported(op))
    } else {
        match request.get("args") {
            None | Some(Value::Null) => call(adapter, op, &Value::Object(Map::new())),
            Some(args @ Value::Object(_)) => call(adapter, op, args),
            Some(_) => Err(AdapterError::invalid_arguments("args is a JSON object")),
        }
    };
    match outcome {
        Ok(result) => (json!({"id": id, "ok": true, "result": result}), false),
        Err(error) => (failure(id, &error), false),
    }
}

/// Calls an operation, and turns a panic into an `internal` error so that the adapter keeps
/// running. The panic hook has already logged it to stderr.
fn call<A: LwsDriverAdapter + ?Sized>(
    adapter: &mut A,
    op: &str,
    args: &Value,
) -> Result<Value, AdapterError> {
    match panic::catch_unwind(AssertUnwindSafe(|| dispatch(adapter, op, args))) {
        Ok(Ok(Value::Null)) => Ok(Value::Object(Map::new())),
        Ok(Ok(result @ Value::Object(_))) => Ok(result),
        Ok(Ok(_)) => {
            eprintln!("lws-driver-adapter: {op} returned a result that is not a JSON object");
            Err(AdapterError::internal(format!(
                "{op} returned a result that is not a JSON object"
            )))
        }
        Ok(Err(error)) => Err(error),
        Err(panic) => Err(AdapterError::internal(format!(
            "{op} panicked: {}",
            panic_message(panic.as_ref())
        ))),
    }
}

fn dispatch<A: LwsDriverAdapter + ?Sized>(
    a: &mut A,
    op: &str,
    args: &Value,
) -> Result<Value, AdapterError> {
    match op {
        "configure" => a.configure(args),
        "discover_storage" => a.discover_storage(args),
        "head" => a.head(args),
        "read" => a.read(args),
        "list_container" => a.list_container(args),
        "create" => a.create(args),
        "create_container" => a.create_container(args),
        "update" => a.update(args),
        "patch" => a.patch(args),
        "delete" => a.delete(args),
        "read_linkset" => a.read_linkset(args),
        "update_linkset" => a.update_linkset(args),
        "patch_linkset" => a.patch_linkset(args),
        "move" => a.move_resource(args),
        "request_access" => a.request_access(args),
        "get_access_request" => a.get_access_request(args),
        "list_access_requests" => a.list_access_requests(args),
        "cancel_access_request" => a.cancel_access_request(args),
        "grant_access" => a.grant_access(args),
        "get_access_grant" => a.get_access_grant(args),
        "list_access_grants" => a.list_access_grants(args),
        "revoke_access_grant" => a.revoke_access_grant(args),
        "subscribe" => a.subscribe(args),
        "list_subscriptions" => a.list_subscriptions(args),
        "get_subscription" => a.get_subscription(args),
        "unsubscribe" => a.unsubscribe(args),
        "verify_notification" => a.verify_notification(args),
        "list_types" => a.list_types(args),
        "search" => a.search(args),
        _ => Err(AdapterError::unsupported(op)),
    }
}

fn panic_message(panic: &(dyn Any + Send)) -> &str {
    if let Some(message) = panic.downcast_ref::<&str>() {
        message
    } else if let Some(message) = panic.downcast_ref::<String>() {
        message
    } else {
        "a panic without a message"
    }
}

/// The answer to a line that is not a JSON object.
fn not_a_request() -> Value {
    failure(
        Value::Null,
        &AdapterError::invalid_arguments("a request is a JSON object"),
    )
}

fn failure(id: Value, error: &AdapterError) -> Value {
    json!({"id": id, "ok": false, "error": error.to_json()})
}

fn write(output: &mut impl Write, message: &Value) -> io::Result<()> {
    let mut line = message.to_string();
    line.push('\n');
    output.write_all(line.as_bytes())?;
    output.flush()
}
