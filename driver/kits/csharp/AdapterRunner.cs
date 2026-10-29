using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Example.LwsDriver;

/// <summary>
/// The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3): it
/// writes the hello, reads one request a line from stdin, calls the method of
/// <see cref="ILwsDriverAdapter"/> for its operation, and writes one response a line to stdout.
/// stdout carries protocol lines only: log to stderr.
/// </summary>
/// <example><code>
/// public static async Task Main() =&gt; Environment.Exit(await AdapterRunner.RunAsync(new MyAdapter()));
/// </code></example>
/// <remarks>Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.</remarks>
public static class AdapterRunner
{
    /// <summary>The protocol this kit speaks.</summary>
    public const string Protocol = "touchstone-driver/1";

    /// <summary>The kinds of <c>configure</c> authentication the protocol defines (section 5.1).</summary>
    public static readonly IReadOnlyList<string> AuthKinds = ["none", "bearer", "cid", "openid", "saml"];

    /// <summary>Every operation of section 5 but shutdown, with the interface method each calls.</summary>
    private static readonly (string Op, string Method, Func<ILwsDriverAdapter, JsonObject, Task<JsonObject>> Call)[] Operations =
    [
        ("configure", nameof(ILwsDriverAdapter.ConfigureAsync), (a, args) => a.ConfigureAsync(args)),
        ("discover_storage", nameof(ILwsDriverAdapter.DiscoverStorageAsync), (a, args) => a.DiscoverStorageAsync(args)),
        ("head", nameof(ILwsDriverAdapter.HeadAsync), (a, args) => a.HeadAsync(args)),
        ("read", nameof(ILwsDriverAdapter.ReadAsync), (a, args) => a.ReadAsync(args)),
        ("list_container", nameof(ILwsDriverAdapter.ListContainerAsync), (a, args) => a.ListContainerAsync(args)),
        ("create", nameof(ILwsDriverAdapter.CreateAsync), (a, args) => a.CreateAsync(args)),
        ("create_container", nameof(ILwsDriverAdapter.CreateContainerAsync), (a, args) => a.CreateContainerAsync(args)),
        ("update", nameof(ILwsDriverAdapter.UpdateAsync), (a, args) => a.UpdateAsync(args)),
        ("patch", nameof(ILwsDriverAdapter.PatchAsync), (a, args) => a.PatchAsync(args)),
        ("delete", nameof(ILwsDriverAdapter.DeleteAsync), (a, args) => a.DeleteAsync(args)),
        ("read_linkset", nameof(ILwsDriverAdapter.ReadLinksetAsync), (a, args) => a.ReadLinksetAsync(args)),
        ("update_linkset", nameof(ILwsDriverAdapter.UpdateLinksetAsync), (a, args) => a.UpdateLinksetAsync(args)),
        ("patch_linkset", nameof(ILwsDriverAdapter.PatchLinksetAsync), (a, args) => a.PatchLinksetAsync(args)),
        ("move", nameof(ILwsDriverAdapter.MoveAsync), (a, args) => a.MoveAsync(args)),
        ("request_access", nameof(ILwsDriverAdapter.RequestAccessAsync), (a, args) => a.RequestAccessAsync(args)),
        ("get_access_request", nameof(ILwsDriverAdapter.GetAccessRequestAsync), (a, args) => a.GetAccessRequestAsync(args)),
        ("list_access_requests", nameof(ILwsDriverAdapter.ListAccessRequestsAsync), (a, args) => a.ListAccessRequestsAsync(args)),
        ("cancel_access_request", nameof(ILwsDriverAdapter.CancelAccessRequestAsync), (a, args) => a.CancelAccessRequestAsync(args)),
        ("grant_access", nameof(ILwsDriverAdapter.GrantAccessAsync), (a, args) => a.GrantAccessAsync(args)),
        ("get_access_grant", nameof(ILwsDriverAdapter.GetAccessGrantAsync), (a, args) => a.GetAccessGrantAsync(args)),
        ("list_access_grants", nameof(ILwsDriverAdapter.ListAccessGrantsAsync), (a, args) => a.ListAccessGrantsAsync(args)),
        ("revoke_access_grant", nameof(ILwsDriverAdapter.RevokeAccessGrantAsync), (a, args) => a.RevokeAccessGrantAsync(args)),
        ("subscribe", nameof(ILwsDriverAdapter.SubscribeAsync), (a, args) => a.SubscribeAsync(args)),
        ("list_subscriptions", nameof(ILwsDriverAdapter.ListSubscriptionsAsync), (a, args) => a.ListSubscriptionsAsync(args)),
        ("get_subscription", nameof(ILwsDriverAdapter.GetSubscriptionAsync), (a, args) => a.GetSubscriptionAsync(args)),
        ("unsubscribe", nameof(ILwsDriverAdapter.UnsubscribeAsync), (a, args) => a.UnsubscribeAsync(args)),
        ("verify_notification", nameof(ILwsDriverAdapter.VerifyNotificationAsync), (a, args) => a.VerifyNotificationAsync(args)),
        ("list_types", nameof(ILwsDriverAdapter.ListTypesAsync), (a, args) => a.ListTypesAsync(args)),
        ("search", nameof(ILwsDriverAdapter.SearchAsync), (a, args) => a.SearchAsync(args)),
    ];

    /// <summary>
    /// Serves the driver on stdin and stdout until end of input or shutdown, and returns the exit
    /// status: 0 then, 1 when stdout breaks.
    /// </summary>
    public static async Task<int> RunAsync(ILwsDriverAdapter adapter)
    {
        // stdout carries protocol lines only: keep it for them, and send Console.Out to stderr, so
        // that a stray Console.WriteLine in your adapter or your client lands in the log.
        using var output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
        Console.SetOut(Console.Error);
        using var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        try
        {
            await ServeAsync(adapter, input, output);
            return 0;
        }
        catch (IOException e)
        {
            Console.Error.WriteLine($"lws-driver-adapter: {e.Message}");
            return 1;
        }
    }

    /// <summary>Serves the driver on these streams until end of input or shutdown.</summary>
    public static async Task ServeAsync(ILwsDriverAdapter adapter, TextReader input, TextWriter output)
    {
        List<string> operations = Listed(adapter);
        await WriteAsync(output, Hello(adapter, operations));
        while (await input.ReadLineAsync() is { } line)
        {
            if (string.IsNullOrWhiteSpace(line))
            {
                continue;
            }
            (JsonObject response, bool last) = await RespondAsync(adapter, operations, line);
            await WriteAsync(output, response);
            if (last)
            {
                return;
            }
        }
    }

    /// <summary>
    /// The operations the hello lists: configure, then each one the adapter's class implements
    /// itself, rather than leaving the interface's default, in the protocol's order.
    /// </summary>
    private static List<string> Listed(ILwsDriverAdapter adapter)
    {
        InterfaceMapping map = adapter.GetType().GetInterfaceMap(typeof(ILwsDriverAdapter));
        var implemented = new HashSet<string>();
        for (int i = 0; i < map.InterfaceMethods.Length; i++)
        {
            if (map.TargetMethods[i]?.DeclaringType is { } type && type != typeof(ILwsDriverAdapter))
            {
                implemented.Add(map.InterfaceMethods[i].Name);
            }
        }
        return Operations.Where(o => o.Op == "configure" || implemented.Contains(o.Method)).Select(o => o.Op).ToList();
    }

    /// <summary>The hello: the client, its authentication, and every operation the adapter implements.</summary>
    private static JsonObject Hello(ILwsDriverAdapter adapter, IEnumerable<string> operations)
    {
        ClientInfo info = adapter.Client;
        var client = new JsonObject { ["name"] = info.Name };
        if (info.Version is not null)
        {
            client["version"] = info.Version;
        }
        if (info.Homepage is not null)
        {
            client["homepage"] = info.Homepage;
        }
        var auth = new List<string>();
        foreach (string kind in adapter.Auth)
        {
            if (!AuthKinds.Contains(kind))
            {
                Console.Error.WriteLine($"lws-driver-adapter: \"{kind}\" is not an authentication kind of {Protocol}: left out of the hello");
            }
            else if (!auth.Contains(kind))
            {
                auth.Add(kind);
            }
        }
        return new JsonObject
        {
            ["hello"] = new JsonObject
            {
                ["protocol"] = Protocol,
                ["client"] = client,
                ["language"] = "csharp",
                ["auth"] = Strings(auth),
                ["operations"] = Strings(operations),
            },
        };
    }

    /// <summary>The response to one request line, and whether it was the last.</summary>
    private static async Task<(JsonObject Response, bool Last)> RespondAsync(
        ILwsDriverAdapter adapter, List<string> operations, string line)
    {
        JsonObject request;
        long id;
        try
        {
            if (JsonNode.Parse(line) is not JsonObject parsed)
            {
                return (NotARequest(), false);
            }
            request = parsed;
            if (request["id"] is not JsonValue idValue || !idValue.TryGetValue(out id) || id < 1)
            {
                return (Failure(null, AdapterException.InvalidArguments("a request has a positive integer id")), false);
            }
        }
        catch (Exception e) when (e is JsonException or ArgumentException or InvalidOperationException)
        {
            return (NotARequest(), false);
        }
        string? op = request["op"] is JsonValue opValue && opValue.TryGetValue(out string? name) ? name : null;
        if (op == "shutdown")
        {
            return (Success(id, new JsonObject()), true);
        }
        try
        {
            return (Success(id, await CallAsync(adapter, operations, op, request)), false);
        }
        catch (AdapterException e)
        {
            return (Failure(id, e), false);
        }
        catch (ArgumentException e)
        {
            return (Failure(id, AdapterException.InvalidArguments(e.Message)), false);
        }
        catch (Exception e)
        {
            // A bug in the adapter or the client: report it, and keep running.
            Console.Error.WriteLine(e);
            return (Failure(id, AdapterException.Internal($"{e.GetType().FullName}: {e.Message}")), false);
        }
    }

    /// <summary>Calls the request's operation when the hello lists it, and answers unsupported otherwise.</summary>
    private static async Task<JsonObject> CallAsync(
        ILwsDriverAdapter adapter, List<string> operations, string? op, JsonObject request)
    {
        var operation = Array.Find(Operations, o => o.Op == op);
        if (operation.Call is null)
        {
            string given = request["op"]?.ToJsonString() ?? "null";
            throw new AdapterException("unsupported", null, $"{given} is not an operation of {Protocol}");
        }
        if (!operations.Contains(operation.Op))
        {
            throw AdapterException.Unsupported(operation.Op);
        }
        JsonObject args;
        switch (request["args"])
        {
            case null:
                args = new JsonObject();
                break;
            case JsonObject given:
                request.Remove("args"); // the adapter owns its arguments: it may put them in a result
                args = given;
                break;
            default:
                throw AdapterException.InvalidArguments("args is a JSON object");
        }
        JsonObject? result = await operation.Call(adapter, args);
        if (result is null)
        {
            return new JsonObject();
        }
        return result.Parent is null ? result : result.DeepClone().AsObject();
    }

    private static JsonObject NotARequest() =>
        Failure(null, AdapterException.InvalidArguments("a request is a JSON object"));

    private static JsonObject Success(long id, JsonObject result) =>
        new() { ["id"] = id, ["ok"] = true, ["result"] = result };

    private static JsonObject Failure(long? id, AdapterException e)
    {
        var error = new JsonObject { ["kind"] = e.Kind };
        if (e.Status is { } status)
        {
            error["status"] = status;
        }
        error["message"] = string.IsNullOrEmpty(e.Message) ? e.Kind : e.Message;
        if (e.Problem is { } problem)
        {
            error["problem"] = problem.DeepClone();
        }
        return new JsonObject
        {
            ["id"] = id is { } n ? JsonValue.Create(n) : null,
            ["ok"] = false,
            ["error"] = error,
        };
    }

    private static JsonArray Strings(IEnumerable<string> values) =>
        new(values.Select(v => (JsonNode?)JsonValue.Create(v)).ToArray());

    private static async Task WriteAsync(TextWriter output, JsonObject message)
    {
        await output.WriteAsync(message.ToJsonString() + "\n");
        await output.FlushAsync();
    }
}
