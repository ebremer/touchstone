using System.Text.Json.Nodes;

namespace Example.LwsDriver;

/// <summary>
/// The starting point: an adapter that implements only <c>ConfigureAsync</c>, so every other
/// operation answers <c>unsupported</c>. It passes <c>touchstone driver check</c> as it is. Replace
/// the client name, keep your client in a field that <see cref="ConfigureAsync"/> builds, and
/// implement the operations your client has, one by one.
/// </summary>
public sealed class StubAdapter : ILwsDriverAdapter
{
    public ClientInfo Client => new("My LWS client (C#)", "0.0.0");

    public IReadOnlyCollection<string> Auth => ["none"];

    public Task<JsonObject> ConfigureAsync(JsonObject args)
    {
        string? type = args["auth"]?["type"]?.GetValue<string>();
        if (type is null || !Auth.Contains(type))
        {
            throw AdapterException.Unsupported($"authentication {type}");
        }
        // Build your client here, with the authentication args["auth"] describes.
        return Task.FromResult(new JsonObject());
    }

    public static async Task Main() => Environment.Exit(await AdapterRunner.RunAsync(new StubAdapter()));
}
