#pragma once

#include <optional>
#include <stdexcept>
#include <string>
#include <utility>

namespace lwsdriver {

/// An operation's outcome that is not a result (driver/PROTOCOL.md section 4.2): it is reported to
/// the driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly
/// what a drive may be looking for.
class AdapterError : public std::runtime_error {
public:
    /// `kind` is http, authentication, protocol, verification, transport, invalidArguments,
    /// unsupported or internal. `status` is the HTTP status of the response that caused it, if
    /// one did; http requires it.
    AdapterError(std::string kind, std::optional<int> status, const std::string& message)
        : std::runtime_error(message), kind_(std::move(kind)), status_(status) {}

    const std::string& kind() const noexcept { return kind_; }
    std::optional<int> status() const noexcept { return status_; }

    /// A response with a status the operation does not succeed with.
    static AdapterError http(int status, const std::string& message) {
        return AdapterError("http", status, message);
    }

    /// The operation, or an argument it was given, is not available in this client.
    static AdapterError unsupported(const std::string& what) {
        return AdapterError("unsupported", std::nullopt, what + " is not available in this client");
    }

    /// The request's arguments are missing or malformed.
    static AdapterError invalid_arguments(const std::string& message) {
        return AdapterError("invalidArguments", std::nullopt, message);
    }

    /// No response: connection refused, TLS failure, timeout.
    static AdapterError transport(const std::string& message) {
        return AdapterError("transport", std::nullopt, message);
    }

private:
    std::string kind_;
    std::optional<int> status_;
};

}  // namespace lwsdriver
