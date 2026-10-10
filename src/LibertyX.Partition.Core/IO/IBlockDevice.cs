namespace LibertyX.Partition.Core.IO;

/// <summary>
/// Qualquer coisa que se lê por setores: um disco físico, um volume, uma imagem
/// .img, ou uma fatia de outro dispositivo (uma partição).
/// Leituras e escritas usam deslocamento e tamanho múltiplos de <see cref="SectorSize"/>;
/// para o resto existe <see cref="BlockDeviceExtensions.ReadBytes"/>.
/// </summary>
public interface IBlockDevice : IDisposable
{
    string Name { get; }
    long Length { get; }
    int SectorSize { get; }
    bool CanWrite { get; }

    /// <summary>Lê a partir de <paramref name="offset"/>. Só devolve menos que o pedido no fim do dispositivo.</summary>
    /// <exception cref="MediaReadException">setor ilegível</exception>
    int Read(long offset, Span<byte> buffer);
    void Write(long offset, ReadOnlySpan<byte> buffer);
    void Flush();
}

/// <summary>Setor que o disco não conseguiu entregar (CRC, erro de mídia, tempo esgotado).</summary>
public sealed class MediaReadException(long offset, int length, int nativeError, string message)
    : IOException(message)
{
    public long Offset { get; } = offset;
    public int Length { get; } = length;
    public int NativeError { get; } = nativeError;
}

public static class BlockDeviceExtensions
{
    /// <summary>Leitura sem exigência de alinhamento: arredonda para setores por fora e copia o miolo.</summary>
    public static int ReadBytes(this IBlockDevice dev, long offset, Span<byte> buffer)
    {
        if (buffer.Length == 0 || offset >= dev.Length) return 0;
        int ss = dev.SectorSize;
        long start = offset / ss * ss;
        long end = Math.Min(dev.Length, (offset + buffer.Length + ss - 1) / ss * ss);
        if (start == offset && end - start == buffer.Length)
            return dev.Read(offset, buffer);
        int len = (int)(end - start);
        byte[] tmp = System.Buffers.ArrayPool<byte>.Shared.Rent(len);
        try
        {
            int got = dev.Read(start, tmp.AsSpan(0, len));
            int skip = (int)(offset - start);
            int n = Math.Max(0, Math.Min(buffer.Length, got - skip));
            tmp.AsSpan(skip, n).CopyTo(buffer);
            return n;
        }
        finally { System.Buffers.ArrayPool<byte>.Shared.Return(tmp); }
    }

    public static byte[] ReadBytes(this IBlockDevice dev, long offset, int count)
    {
        var b = new byte[count];
        int n = dev.ReadBytes(offset, b);
        if (n < count) Array.Resize(ref b, n);
        return b;
    }

    /// <summary>Escrita sem exigência de alinhamento: lê, altera e regrava os setores tocados.</summary>
    public static void WriteBytes(this IBlockDevice dev, long offset, ReadOnlySpan<byte> data)
    {
        int ss = dev.SectorSize;
        long start = offset / ss * ss;
        long end = (offset + data.Length + ss - 1) / ss * ss;
        if (start == offset && end - start == data.Length) { dev.Write(offset, data); return; }
        var tmp = new byte[end - start];
        dev.Read(start, tmp);
        data.CopyTo(tmp.AsSpan((int)(offset - start)));
        dev.Write(start, tmp);
    }

    public static IBlockDevice Slice(this IBlockDevice dev, long offset, long length, string? name = null)
        => new SubDevice(dev, offset, length, name);
}
