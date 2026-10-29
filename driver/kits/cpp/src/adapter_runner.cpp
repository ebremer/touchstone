#include "lwsdriver/adapter_runner.hpp"

#include <cstdio>
#include <exception>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <typeinfo>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

namespace lwsdriver {
namespace {

/// Protocol messages keep their members in the order written, for a readable log.
using OrderedJson = nlohmann::ordered_json;

constexpr auto kReplace = Json::error_handler_t::replace;

/// The hello: the client, its authentication, and every operation the adapter implements.
std::string hello(const LwsDriverAdapter& adapter, const std::vector<Operation>& operations) {
    const ClientInfo info = adapter.client();
    OrderedJson client;
    client["name"] = info.name;
    if (info.version) client["version"] = *info.version;
    if (info.homepage) client["homepage"] = *info.homepage;
    OrderedJson auth = OrderedJson::array();
    for (const std::string& kind : adapter.auth()) auth.push_back(kind);
    OrderedJson ops = OrderedJson::array();
    for (const Operation& op : operations) {
        if (op.implemented) ops.push_back(op.name);
    }
    OrderedJson h;
    h["protocol"] = kProtocol;
    h["client"] = client;
    h["language"] = "cpp";
    h["auth"] = auth;
    h["operations"] = ops;
    OrderedJson message;
    message["hello"] = h;
    return message.dump(-1, ' ', false, kReplace);
}

/// A result that is not valid UTF-8 throws here, and is reported as internal.
std::string ok(const Json& id, const Json& result) {
    return "{\"id\":" + id.dump(-1, ' ', false, kReplace) + ",\"ok\":true,\"result\":" + result.dump() + "}";
}

std::string error(const Json& id, const std::string& kind, std::optional<int> status, const std::string& message) {
    OrderedJson e;
    e["kind"] = kind;
    if (status) e["status"] = *status;
    e["message"] = message.empty() ? kind : message;
    return "{\"id\":" + id.dump(-1, ' ', false, kReplace) + ",\"ok\":false,\"error\":" + e.dump(-1, ' ', false, kReplace) + "}";
}

/// Runs one operation and answers it: a result, or the error it ended with.
std::string respond(LwsDriverAdapter& adapter, const std::vector<Operation>& operations, const Json& id,
                    const std::string& op, const Json& args) {
    try {
        const Operation* call = nullptr;
        for (const Operation& o : operations) {
            if (op == o.name) call = &o;
        }
        if (call == nullptr) throw AdapterError::unsupported(op.empty() ? "an operation without a name" : op);
        Json result = (adapter.*(call->method))(args);
        if (result.is_null()) result = Json::object();
        if (!result.is_object()) throw std::logic_error("the result of " + op + " is not a JSON object");
        return ok(id, result);
    } catch (const AdapterError& e) {
        return error(id, e.kind(), e.status(), e.what());
    } catch (const std::invalid_argument& e) {
        return error(id, "invalidArguments", std::nullopt, e.what());
    } catch (const std::exception& e) {
        // A bug in the adapter or the client: report it, and keep running.
        std::cerr << "lwsdriver: " << op << ": " << typeid(e).name() << ": " << e.what() << std::endl;
        return error(id, "internal", std::nullopt, e.what());
    } catch (...) {
        std::cerr << "lwsdriver: " << op << ": an exception that is not a std::exception" << std::endl;
        return error(id, "internal", std::nullopt, "an exception that is not a std::exception");
    }
}

bool blank(const std::string& line) {
    return line.find_first_not_of(" \t\r") == std::string::npos;
}

/// One line, flushed. False when stdout broke: the driver is gone.
bool write(std::ostream& out, const std::string& line) {
    out << line << '\n';
    out.flush();
    return static_cast<bool>(out);
}

}  // namespace

int serve(LwsDriverAdapter& adapter, const std::vector<Operation>& operations, std::istream& in, std::ostream& out) {
    std::string greeting;
    try {
        greeting = hello(adapter, operations);
    } catch (const std::exception& e) {
        std::cerr << "lwsdriver: the hello: " << e.what() << std::endl;
        return 1;
    }
    if (!write(out, greeting)) return 1;
    std::string line;
    while (std::getline(in, line)) {
        if (blank(line)) continue;
        const Json request = Json::parse(line, nullptr, false);
        if (request.is_discarded() || !request.is_object()) {
            if (!write(out, error(nullptr, "invalidArguments", std::nullopt, "a request is a JSON object"))) return 1;
            continue;
        }
        const Json id = request.contains("id") ? request.at("id") : Json(nullptr);
        const Json* name = member(request, "op");
        const std::string op = name != nullptr && name->is_string() ? name->get<std::string>() : "";
        if (op == "shutdown") {
            write(out, ok(id, Json::object()));
            return 0;
        }
        const Json* given = member(request, "args");
        const Json args = given != nullptr && given->is_object() ? *given : Json::object();
        if (!write(out, respond(adapter, operations, id, op, args))) return 1;
    }
    return 0;
}

int serve_stdio(LwsDriverAdapter& adapter, const std::vector<Operation>& operations) {
#ifdef _WIN32
    // Text mode would turn "\n" into "\r\n".
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    // stdout carries protocol lines only: what the program writes to std::cout goes to stderr.
    std::ostream protocol(std::cout.rdbuf());
    std::streambuf* const stdout_buffer = std::cout.rdbuf(std::cerr.rdbuf());
    const int status = serve(adapter, operations, std::cin, protocol);
    std::cout.rdbuf(stdout_buffer);
    return status;
}

}  // namespace lwsdriver
