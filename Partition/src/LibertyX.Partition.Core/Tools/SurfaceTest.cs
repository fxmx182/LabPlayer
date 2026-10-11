using System.Diagnostics;
using LibertyX.Partition.Core.IO;

namespace LibertyX.Partition.Core.Tools;

public enum BlockHealth : byte { Pending, Good, Slow, VerySlow, Bad }

public readonly record struct SurfaceProgress(int BlockIndex, BlockHealth Health, double Millis, long Done, long Total, double BytesPerSecond, long BadSectors);

public sealed class SurfaceResult
{
    public List<long> BadSectors { get; } = [];
    public int Good, Slow, VerySlow, Bad;
    public TimeSpan Elapsed;
    public double AvgMBps;
}

/// <summary>
/// Teste de superfície: lê cada bloco do disco e cronometra. Setor que não lê é ruim; setor que
/// lê devagar demais é o aviso de que a mídia está degradando — e é esse o dado que importa
/// antes de tentar qualquer recuperação.
/// </summary>
public static class SurfaceTest
{
    public const double SlowMs = 150, VerySlowMs = 500;

    public static SurfaceResult Run(IBlockDevice dev, long start, long length, int blockCount, IProgress<SurfaceProgress>? progress,
                                    CancellationToken ct, ManualResetEventSlim? pause = null)
    {
        int ss = dev.SectorSize;
        long blockBytes = Math.Max(ss, (length / blockCount + ss - 1) / ss * ss);
        int ioSize = (int)Math.Min(1 << 20, blockBytes);
        var buf = new byte[ioSize];
        var res = new SurfaceResult();
        var total = Stopwatch.StartNew();
        long done = 0;
        int idx = 0;
        for (long b = start; b < start + length; b += blockBytes, idx++)
        {
            ct.ThrowIfCancellationRequested();
            pause?.Wait(ct);
            long end = Math.Min(start + length, b + blockBytes);
            double worst = 0;
            bool bad = false;
            for (long p = b; p < end; p += ioSize)
            {
                int n = (int)Math.Min(ioSize, end - p);
                var sw = Stopwatch.StartNew();
                try { dev.Read(p, buf.AsSpan(0, n)); }
                catch (MediaReadException)
                {
                    bad = true;
                    for (long q = p; q < p + n; q += ss)
                    {
                        try { dev.Read(q, buf.AsSpan(0, ss)); }
                        catch (MediaReadException) { res.BadSectors.Add(q / ss); }
                    }
                }
                worst = Math.Max(worst, sw.Elapsed.TotalMilliseconds * (1 << 20) / Math.Max(n, 1)); // ms por MiB
                done += n;
            }
            var h = bad ? BlockHealth.Bad : worst >= VerySlowMs ? BlockHealth.VerySlow : worst >= SlowMs ? BlockHealth.Slow : BlockHealth.Good;
            switch (h) { case BlockHealth.Good: res.Good++; break; case BlockHealth.Slow: res.Slow++; break; case BlockHealth.VerySlow: res.VerySlow++; break; default: res.Bad++; break; }
            progress?.Report(new(idx, h, worst, done, length, done / Math.Max(0.001, total.Elapsed.TotalSeconds), res.BadSectors.Count));
        }
        res.Elapsed = total.Elapsed;
        res.AvgMBps = done / 1e6 / Math.Max(0.001, total.Elapsed.TotalSeconds);
        return res;
    }
}
