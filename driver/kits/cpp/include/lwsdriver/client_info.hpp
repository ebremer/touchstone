#pragma once

#include <optional>
#include <string>

namespace lwsdriver {

/// The client under test, as the hello names it (driver/PROTOCOL.md section 3): it becomes the
/// subject of the session's EARL report.
struct ClientInfo {
    /// The client's name.
    std::string name;
    /// Its version, if you give one.
    std::optional<std::string> version;
    /// An absolute http(s) URL, if you give one.
    std::optional<std::string> homepage;
};

}  // namespace lwsdriver
