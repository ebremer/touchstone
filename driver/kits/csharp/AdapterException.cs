using System.Text.Json.Nodes;

namespace Example.LwsDriver;

/// <summary>
/// An operation's outcome that is not a result (driver/PROTOCOL.md section 4.2): it is reported to
/// the driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly
/// what a drive may be looking for.
/// </summary>
public class AdapterException : Exception
{
    /// <param name="kind">http, authentication, protocol, verification, transport, invalidArguments,
    /// unsupported or internal.</param>
    /// <param name="status">The HTTP status of the response that caused it, or null; required for http.</param>
    /// <param name="message">What happened, for a person reading the drive.</param>
    /// <param name="inner">The client's own exception, if any.</param>
    public AdapterException(string kind, int? status, string message, Exception? inner = null)
        : base(message, inner)
    {
        Kind = kind;
        Status = status;
    }

    /// <summary>http, authentication, protocol, verification, transport, invalidArguments, unsupported or internal.</summary>
    public string Kind { get; }

    /// <summary>The HTTP status of the response that caused it, or null; required for http.</summary>
    public int? Status { get; }

    /// <summary>An RFC 9457 problem details object, when the response carried one.</summary>
    public JsonObject? Problem { get; init; }

    /// <summary>A response with a status the operation does not succeed with.</summary>
    public static AdapterException Http(int status, string message) => new("http", status, message);

    /// <summary>Obtaining or using a credential failed; <paramref name="status"/> when a response caused it.</summary>
    public static AdapterException Authentication(string message, int? status = null) =>
        new("authentication", status, message);

    /// <summary>A response the client found to break the specification.</summary>
    public static AdapterException Protocol(string message) => new("protocol", null, message);

    /// <summary>A notification that failed verification.</summary>
    public static AdapterException Verification(string message) => new("verification", null, message);

    /// <summary>No response: connection refused, TLS failure, timeout.</summary>
    public static AdapterException Transport(string message) => new("transport", null, message);

    /// <summary>The request's arguments are missing or malformed.</summary>
    public static AdapterException InvalidArguments(string message) => new("invalidArguments", null, message);

    /// <summary>The operation, or an argument it was given, is not available in this client.</summary>
    public static AdapterException Unsupported(string what) =>
        new("unsupported", null, $"{what} is not available in this client");

    /// <summary>Anything else, which is a bug in the adapter or the client.</summary>
    public static AdapterException Internal(string message) => new("internal", null, message);
}
