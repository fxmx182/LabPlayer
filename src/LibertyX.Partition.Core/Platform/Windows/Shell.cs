using System.Runtime.Versioning;

namespace LibertyX.Partition.Core.Platform.Windows;

[SupportedOSPlatform("windows")]
public static class Shell
{
    /// <summary>Manda para a Lixeira (desfazível), em lotes.</summary>
    public static bool Recycle(IEnumerable<string> paths)
    {
        bool ok = true;
        foreach (var chunk in paths.Chunk(200)) ok &= Native.Recycle(chunk);
        return ok;
    }
}
