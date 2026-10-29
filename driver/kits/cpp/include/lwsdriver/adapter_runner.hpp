#pragma once

#include <iosfwd>
#include <type_traits>
#include <vector>

#include "lwsdriver/lws_driver_adapter.hpp"

namespace lwsdriver {

/// The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3): it
/// writes the hello, reads one request a line from stdin, calls the method of LwsDriverAdapter for
/// its operation, and writes one response a line to stdout. stdout carries protocol lines only: log
/// to stderr (std::cerr). While it serves, std::cout writes to stderr too, but printf and anything
/// else writing to stdout would break the protocol.
///
///     int main() {
///         MyAdapter adapter;
///         return lwsdriver::serve(adapter);
///     }
///
/// Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.

inline constexpr const char* kProtocol = "touchstone-driver/1";

/// One operation of the protocol: how the runner calls it, and whether the adapter implements it.
struct Operation {
    const char* name;
    Json (LwsDriverAdapter::*method)(const Json&);
    bool implemented;
};

namespace detail {

using Method = Json (LwsDriverAdapter::*)(const Json&);

/// `&A::read` names LwsDriverAdapter::read, its default, unless A (or a class between) overrides
/// it: then its type is a pointer to a member of that class. That is how the runner knows which
/// operations you implement, at compile time. The cast also refuses an override with another
/// signature.
template <class M>
Operation operation(const char* name, M method) {
    return {name, static_cast<Method>(method), !std::is_same_v<M, Method>};
}

}  // namespace detail

/// Every operation of section 5 but shutdown, for the adapter class A.
template <class A>
std::vector<Operation> operations_of() {
    using detail::operation;
    return {
        operation("configure", &A::configure),
        operation("discover_storage", &A::discover_storage),
        operation("head", &A::head),
        operation("read", &A::read),
        operation("list_container", &A::list_container),
        operation("create", &A::create),
        operation("create_container", &A::create_container),
        operation("update", &A::update),
        operation("patch", &A::patch),
        operation("delete", &A::delete_),
        operation("read_linkset", &A::read_linkset),
        operation("update_linkset", &A::update_linkset),
        operation("patch_linkset", &A::patch_linkset),
        operation("move", &A::move),
        operation("request_access", &A::request_access),
        operation("get_access_request", &A::get_access_request),
        operation("list_access_requests", &A::list_access_requests),
        operation("cancel_access_request", &A::cancel_access_request),
        operation("grant_access", &A::grant_access),
        operation("get_access_grant", &A::get_access_grant),
        operation("list_access_grants", &A::list_access_grants),
        operation("revoke_access_grant", &A::revoke_access_grant),
        operation("subscribe", &A::subscribe),
        operation("list_subscriptions", &A::list_subscriptions),
        operation("get_subscription", &A::get_subscription),
        operation("unsubscribe", &A::unsubscribe),
        operation("verify_notification", &A::verify_notification),
        operation("list_types", &A::list_types),
        operation("search", &A::search),
    };
}

/// Serves the driver on these streams until end of input or `shutdown`, and returns the exit
/// status: 0, or 1 when stdout broke.
int serve(LwsDriverAdapter& adapter, const std::vector<Operation>& operations, std::istream& in, std::ostream& out);

/// Serves the driver on stdin and stdout, in binary mode on Windows so that a line ends in "\n", with
/// std::cout sent to stderr meanwhile.
int serve_stdio(LwsDriverAdapter& adapter, const std::vector<Operation>& operations);

/// Serves the driver on stdin and stdout with your adapter. Pass it as its own class, not as an
/// LwsDriverAdapter&: the class is what says which operations it implements.
template <class A>
int serve(A& adapter) {
    static_assert(std::is_base_of_v<LwsDriverAdapter, A> && !std::is_same_v<A, LwsDriverAdapter>,
                  "serve() takes your adapter as its own class, derived from LwsDriverAdapter");
    return serve_stdio(adapter, operations_of<A>());
}

}  // namespace lwsdriver
