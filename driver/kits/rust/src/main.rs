//! The starting point: an adapter that implements only `configure`, so every other operation
//! answers `unsupported`. It passes `touchstone driver check` as it is. Replace the client name,
//! keep your client in a field that `configure` builds, and implement the operations your client
//! has, one by one, naming each in `operations`.

use std::process::ExitCode;

use lws_driver_adapter::{AdapterError, ClientInfo, LwsDriverAdapter};
use serde_json::{Value, json};

#[derive(Default)]
struct StubAdapter {
    // Your client goes here, built by configure. For example: client: Option<MyClient>,
}

impl LwsDriverAdapter for StubAdapter {
    fn client(&self) -> ClientInfo {
        ClientInfo::new("My LWS client (Rust)").with_version("0.0.0")
    }

    fn auth(&self) -> &[&'static str] {
        &["none"]
    }

    fn operations(&self) -> &[&'static str] {
        // Name each operation here as you implement it, for example &["discover_storage", "read"].
        &[]
    }

    fn configure(&mut self, args: &Value) -> Result<Value, AdapterError> {
        let kind = args["auth"]["type"].as_str().unwrap_or_default();
        if !self.auth().contains(&kind) {
            return Err(AdapterError::unsupported(format!("authentication {kind}")));
        }
        // Build your client here, with the authentication args["auth"] describes.
        Ok(json!({}))
    }
}

fn main() -> ExitCode {
    lws_driver_adapter::run(StubAdapter::default())
}
