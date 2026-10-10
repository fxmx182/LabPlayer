namespace LibertyX.Partition.Core.Recovery;

public sealed class ScanOptions
{
    /// <summary>Lista também os arquivos existentes (partição RAW ou formatada: o que interessa é tudo).</summary>
    public bool IncludeLive { get; set; }
    /// <summary>Varredura profunda: escava arquivos por assinatura (JPEG, PDF, MP4...).</summary>
    public bool DeepScan { get; set; }
    /// <summary>Na varredura profunda, pula clusters que o sistema de arquivos diz estarem em uso.</summary>
    public bool CarveFreeSpaceOnly { get; set; } = true;
    /// <summary>Procura registros MFT soltos (NTFS formatado ou com MFT perdido).</summary>
    public bool OrphanMftRecords { get; set; } = true;
    /// <summary>Extensões a escavar; nulo = todas.</summary>
    public HashSet<string>? CarveTypes { get; set; }
}

public readonly record struct ScanProgress(string Phase, double Fraction, long Found, long Position, long Total);

/// <summary>Mapa de alocação do volume: quais bytes pertencem a arquivos vivos.</summary>
public sealed class AllocationMap(long dataStart, long clusterSize, long clusterCount, Func<long, bool> isAllocated)
{
    public long DataStart { get; } = dataStart;
    public long ClusterSize { get; } = clusterSize;
    public long ClusterCount { get; } = clusterCount;
    public Func<long, bool> IsClusterAllocated { get; } = isAllocated;

    /// <summary>Faixas livres (deslocamento no volume, tamanho), juntando clusters vizinhos.</summary>
    public IEnumerable<(long offset, long length)> FreeRanges(long volumeLength)
    {
        if (DataStart > 0) yield return (0, DataStart); // área de metadados: setores de boot, FATs
        long runStart = -1;
        for (long c = 0; c < ClusterCount; c++)
        {
            bool alloc = IsClusterAllocated(c);
            if (!alloc && runStart < 0) runStart = c;
            if (alloc && runStart >= 0) { yield return (DataStart + runStart * ClusterSize, (c - runStart) * ClusterSize); runStart = -1; }
        }
        if (runStart >= 0) yield return (DataStart + runStart * ClusterSize, (ClusterCount - runStart) * ClusterSize);
        long end = DataStart + ClusterCount * ClusterSize;
        if (end < volumeLength) yield return (end, volumeLength - end);
    }
}

public sealed class ScanResult
{
    public required RecoveredItem Root { get; init; }
    public List<RecoveredItem> Items { get; } = [];
    public string FileSystem { get; set; } = "";
    public string? Label { get; set; }
    public AllocationMap? Allocation { get; set; }
    public long ClusterSize { get; set; }
    public List<string> Log { get; } = [];
    private long _nextId = 1_000_000_000_000;
    public long NewId() => Interlocked.Increment(ref _nextId);

    public RecoveredItem GetOrAddFolder(RecoveredItem parent, string name, string? note = null)
    {
        parent.Children ??= [];
        var f = parent.Children.FirstOrDefault(c => c.IsDirectory && c.Name == name);
        if (f != null) return f;
        f = new RecoveredItem { Id = NewId(), Name = name, IsDirectory = true, Origin = ItemOrigin.Carved, Parent = parent, Note = note, ParentId = parent.Id };
        parent.Children.Add(f);
        Items.Add(f);
        return f;
    }

    public void Attach(RecoveredItem parent, RecoveredItem child)
    {
        parent.Children ??= [];
        child.Parent = parent;
        child.ParentId = parent.Id;
        parent.Children.Add(child);
        Items.Add(child);
    }
}
