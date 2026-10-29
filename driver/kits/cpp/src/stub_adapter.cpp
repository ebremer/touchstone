#include <optional>
#include <set>
#include <string>

#include "lwsdriver/adapter_runner.hpp"

using lwsdriver::AdapterError;
using lwsdriver::ClientInfo;
using lwsdriver::Json;

/// The starting point: an adapter that implements only `configure`, so every other operation
/// answers `unsupported`. It passes `touchstone driver check` as it is. Replace the client name,
/// keep your client in a member that configure() builds, and implement the operations your client
/// has, one by one: override them here.
class StubAdapter final : public lwsdriver::LwsDriverAdapter {
public:
    ClientInfo client() const override {
        return {"My LWS client (C++)", "0.0.0", std::nullopt};
    }

    std::set<std::string> auth() const override {
        return {"none"};
    }

    Json configure(const Json& args) override {
        const Json* given = lwsdriver::member(args, "auth");
        const std::string type = given != nullptr ? lwsdriver::string_member(*given, "type").value_or("") : "";
        if (auth().count(type) == 0) throw AdapterError::unsupported("authentication " + type);
        // Build your client here, with the authentication args["auth"] describes.
        return Json::object();
    }
};

int main() {
    StubAdapter adapter;
    return lwsdriver::serve(adapter);
}
