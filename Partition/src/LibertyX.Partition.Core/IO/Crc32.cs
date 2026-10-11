namespace LibertyX.Partition.Core.IO;

/// <summary>CRC-32 (IEEE) — o que o GPT usa no cabeçalho e na tabela de entradas.</summary>
public static class Crc32
{
    public static uint Compute(ReadOnlySpan<byte> data) => System.IO.Hashing.Crc32.HashToUInt32(data);
}
