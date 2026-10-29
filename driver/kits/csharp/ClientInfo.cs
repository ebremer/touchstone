namespace Example.LwsDriver;

/// <summary>
/// The client under test, as the hello names it (driver/PROTOCOL.md section 3): it becomes the
/// subject of the session's EARL report.
/// </summary>
/// <param name="Name">The client's name.</param>
/// <param name="Version">Its version, or null.</param>
/// <param name="Homepage">An absolute http(s) URL, or null.</param>
public sealed record ClientInfo(string Name, string? Version = null, string? Homepage = null);
