namespace LibertyX.Partition.Core.IO;

/// <summary>Um <see cref="Stream"/> somente-leitura sobre um dispositivo, para entregar a decodificadores (prévia de imagem etc.).</summary>
public sealed class DeviceStream(IBlockDevice dev, long start, long length) : Stream
{
    private long _pos;
    public override bool CanRead => true;
    public override bool CanSeek => true;
    public override bool CanWrite => false;
    public override long Length => length;
    public override long Position { get => _pos; set => _pos = Math.Clamp(value, 0, length); }

    public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        int n = (int)Math.Min(buffer.Length, length - _pos);
        if (n <= 0) return 0;
        n = dev.ReadBytes(start + _pos, buffer[..n]);
        _pos += n;
        return n;
    }

    public override long Seek(long offset, SeekOrigin origin)
    {
        Position = origin switch { SeekOrigin.Begin => offset, SeekOrigin.Current => _pos + offset, _ => length + offset };
        return _pos;
    }

    public override void Flush() { }
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}
