//! The adapter error: an operation's outcome that is not a result.

use std::fmt;

use serde_json::{Map, Value};

/// What went wrong, as driver/PROTOCOL.md section 4.2 names it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ErrorKind {
    /// A response with a status the operation does not succeed with. It has a status.
    Http,
    /// Obtaining or using a credential failed: discovery of the authorization server, a check the
    /// client makes on what it discovered, or the token request.
    Authentication,
    /// A response the client found to break the specification.
    Protocol,
    /// A notification that failed verification (`verify_notification`).
    Verification,
    /// No response: connection refused, TLS failure, timeout.
    Transport,
    /// The request's arguments are missing or malformed.
    InvalidArguments,
    /// The operation, or an argument it was given, is not available in this client.
    Unsupported,
    /// Anything else, which is a bug in the adapter or the client.
    Internal,
}

impl ErrorKind {
    /// The kind as the protocol writes it, such as `"invalidArguments"`.
    pub fn as_str(self) -> &'static str {
        match self {
            ErrorKind::Http => "http",
            ErrorKind::Authentication => "authentication",
            ErrorKind::Protocol => "protocol",
            ErrorKind::Verification => "verification",
            ErrorKind::Transport => "transport",
            ErrorKind::InvalidArguments => "invalidArguments",
            ErrorKind::Unsupported => "unsupported",
            ErrorKind::Internal => "internal",
        }
    }
}

/// An operation's outcome that is not a result (driver/PROTOCOL.md section 4.2): it is reported to
/// the driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly
/// what a drive may be looking for.
#[derive(Debug, Clone, PartialEq)]
pub struct AdapterError {
    /// What went wrong.
    pub kind: ErrorKind,
    /// The HTTP status of the response that caused it. Required for [`ErrorKind::Http`].
    pub status: Option<u16>,
    /// What happened, for a person reading the drive.
    pub message: String,
    /// An RFC 9457 problem details object, when the response carried one.
    pub problem: Option<Map<String, Value>>,
}

impl AdapterError {
    /// An error of any kind.
    pub fn new(kind: ErrorKind, status: Option<u16>, message: impl Into<String>) -> Self {
        AdapterError {
            kind,
            status,
            message: message.into(),
            problem: None,
        }
    }

    /// A response with a status the operation does not succeed with.
    pub fn http(status: u16, message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Http, Some(status), message)
    }

    /// Obtaining or using a credential failed. Add the status with [`with_status`](Self::with_status)
    /// when a response caused it.
    pub fn authentication(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Authentication, None, message)
    }

    /// A response the client found to break the specification.
    pub fn protocol(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Protocol, None, message)
    }

    /// A notification that failed verification.
    pub fn verification(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Verification, None, message)
    }

    /// No response: connection refused, TLS failure, timeout.
    pub fn transport(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Transport, None, message)
    }

    /// The request's arguments are missing or malformed.
    pub fn invalid_arguments(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::InvalidArguments, None, message)
    }

    /// The operation, or an argument it was given, is not available in this client.
    pub fn unsupported(what: impl fmt::Display) -> Self {
        Self::new(
            ErrorKind::Unsupported,
            None,
            format!("{what} is not available in this client"),
        )
    }

    /// Anything else, which is a bug in the adapter or the client.
    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Internal, None, message)
    }

    /// The same error, with the HTTP status of the response that caused it.
    #[must_use]
    pub fn with_status(mut self, status: u16) -> Self {
        self.status = Some(status);
        self
    }

    /// The same error, with the RFC 9457 problem details the response carried.
    #[must_use]
    pub fn with_problem(mut self, problem: Map<String, Value>) -> Self {
        self.problem = Some(problem);
        self
    }

    /// The error as a response writes it: `{kind, status?, message, problem?}`.
    pub(crate) fn to_json(&self) -> Value {
        let mut error = Map::new();
        error.insert("kind".into(), self.kind.as_str().into());
        if let Some(status) = self.status {
            error.insert("status".into(), status.into());
        }
        let message = if self.message.is_empty() {
            self.kind.as_str()
        } else {
            &self.message
        };
        error.insert("message".into(), message.into());
        if let Some(problem) = &self.problem {
            error.insert("problem".into(), Value::Object(problem.clone()));
        }
        Value::Object(error)
    }
}

impl fmt::Display for AdapterError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.status {
            Some(status) => write!(f, "{} {}: {}", self.kind.as_str(), status, self.message),
            None => write!(f, "{}: {}", self.kind.as_str(), self.message),
        }
    }
}

impl std::error::Error for AdapterError {}
