namespace LibertyX.Partition.Core.Partitioning;

public enum TableStyle { None, Mbr, Gpt }

/// <summary>Uma entrada da tabela, em setores lógicos do disco.</summary>
public sealed class PartitionEntry
{
    public long StartLba { get; set; }
    public long SectorCount { get; set; }
    public long EndLba => StartLba + SectorCount - 1;

    // GPT
    public Guid TypeGuid { get; set; }
    public Guid UniqueGuid { get; set; }
    public ulong Attributes { get; set; }
    public string Name { get; set; } = "";

    // MBR
    public byte MbrType { get; set; }
    public bool Active { get; set; }
    public bool IsLogical { get; set; }
    /// <summary>Posição (1..n) na ordem em que aparece na tabela. Lógicas começam em 5, como no Linux.</summary>
    public int Slot { get; set; }
    /// <summary>Onde está o EBR que descreve esta lógica (só MBR).</summary>
    public long EbrLba { get; set; }

    public PartitionEntry Clone() => (PartitionEntry)MemberwiseClone();

    public string TypeDescription(TableStyle style) =>
        style == TableStyle.Gpt ? PartitionTypes.GptName(TypeGuid) : PartitionTypes.MbrName(MbrType);
}

/// <summary>A tabela de partições como lida (ou como será escrita).</summary>
public sealed class PartitionTable
{
    public TableStyle Style { get; set; }
    public int SectorSize { get; set; } = 512;
    public long DiskSectors { get; set; }
    public List<PartitionEntry> Entries { get; } = [];

    // GPT
    public Guid DiskGuid { get; set; }
    public long FirstUsableLba { get; set; }
    public long LastUsableLba { get; set; }
    public int GptEntryCount { get; set; } = 128;
    public bool GptBackupDamaged { get; set; }
    public bool GptPrimaryDamaged { get; set; }

    // MBR
    public uint DiskSignature { get; set; }
    public byte[]? BootCode { get; set; }

    /// <summary>Avisos de leitura (cabeçalho GPT corrompido, entradas sobrepostas...).</summary>
    public List<string> Warnings { get; } = [];

    public long UsableStart => Style == TableStyle.Gpt ? FirstUsableLba : 1;
    public long UsableEnd => Style == TableStyle.Gpt ? LastUsableLba : Math.Min(DiskSectors - 1, uint.MaxValue);

    public IEnumerable<PartitionEntry> Ordered => Entries.OrderBy(e => e.StartLba);

    /// <summary>Lacunas livres ≥ <paramref name="minSectors"/> entre as partições.</summary>
    public List<(long start, long count)> FreeRegions(long minSectors)
    {
        var res = new List<(long, long)>();
        long cur = Style == TableStyle.None ? 0 : UsableStart;
        long end = Style == TableStyle.None ? DiskSectors - 1 : UsableEnd;
        foreach (var e in Ordered)
        {
            if (e.StartLba > cur && e.StartLba - cur >= minSectors) res.Add((cur, e.StartLba - cur));
            cur = Math.Max(cur, e.EndLba + 1);
        }
        if (end + 1 - cur >= minSectors) res.Add((cur, end + 1 - cur));
        return res;
    }

    public PartitionTable Clone()
    {
        var copy = new PartitionTable
        {
            Style = Style, SectorSize = SectorSize, DiskSectors = DiskSectors, DiskGuid = DiskGuid,
            FirstUsableLba = FirstUsableLba, LastUsableLba = LastUsableLba, GptEntryCount = GptEntryCount,
            DiskSignature = DiskSignature, BootCode = BootCode?.ToArray(),
        };
        foreach (var e in Entries) copy.Entries.Add(e.Clone());
        return copy;
    }

    /// <summary>Alinhamento padrão do Windows: 1 MiB.</summary>
    public long AlignSectors => 1024 * 1024 / SectorSize;
}
