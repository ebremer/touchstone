//! The Rust starter kit for Touchstone's client driver protocol, `touchstone-driver/1`
//! (driver/PROTOCOL.md).
//!
//! Your adapter implements [`LwsDriverAdapter`] with your client and hands it to [`run`], which does
//! the protocol: it writes the hello, reads one request a line from stdin, calls the method for its
//! operation, and writes one response a line to stdout. stdout carries protocol lines only: log to
//! stderr, with `eprintln!`.
//!
//! ```no_run
//! use lws_driver_adapter::{AdapterError, ClientInfo, LwsDriverAdapter};
//! use serde_json::{Value, json};
//!
//! struct MyAdapter;
//!
//! impl LwsDriverAdapter for MyAdapter {
//!     fn client(&self) -> ClientInfo {
//!         ClientInfo::new("my-client")
//!     }
//!     fn auth(&self) -> &[&'static str] {
//!         &["none"]
//!     }
//!     fn operations(&self) -> &[&'static str] {
//!         &[]
//!     }
//!     fn configure(&mut self, args: &Value) -> Result<Value, AdapterError> {
//!         Ok(json!({}))
//!     }
//! }
//!
//! fn main() -> std::process::ExitCode {
//!     lws_driver_adapter::run(MyAdapter)
//! }
//! ```
//!
//! Copy the library as it is: it follows touchstone-driver/1, and nothing in it is particular to a
//! client. Your adapter is `src/main.rs`.

mod adapter;
mod error;
mod runner;

pub use adapter::{ClientInfo, LwsDriverAdapter};
pub use error::{AdapterError, ErrorKind};
pub use runner::{AUTH_KINDS, OPERATIONS, PROTOCOL, run, serve};
