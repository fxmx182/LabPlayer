using System.Buffers.Binary;
using System.Text;

namespace LibertyX.Partition.Core.IO;

/// <summary>Leituras little-endian curtas, sem cerimônia — as estruturas de disco são todas LE.</summary>
public static class Bin
{
    public static ushort U16(ReadOnlySpan<byte> b, int o) => BinaryPrimitives.ReadUInt16LittleEndian(b[o..]);
    public static uint U32(ReadOnlySpan<byte> b, int o) => BinaryPrimitives.ReadUInt32LittleEndian(b[o..]);
    public static ulong U64(ReadOnlySpan<byte> b, int o) => BinaryPrimitives.ReadUInt64LittleEndian(b[o..]);
    public static long I64(ReadOnlySpan<byte> b, int o) => BinaryPrimitives.ReadInt64LittleEndian(b[o..]);
    public static ushort U16BE(ReadOnlySpan<byte> b, int o) => BinaryPrimitives.ReadUInt16BigEndian(b[o..]);
    public static uint U32BE(ReadOnlySpan<byte> b, int o) => BinaryPrimitives.ReadUInt32BigEndian(b[o..]);
    public static ulong U64BE(ReadOnlySpan<byte> b, int o) => BinaryPrimitives.ReadUInt64BigEndian(b[o..]);
    public static void W16(Span<byte> b, int o, ushort v) => BinaryPrimitives.WriteUInt16LittleEndian(b[o..], v);
    public static void W32(Span<byte> b, int o, uint v) => BinaryPrimitives.WriteUInt32LittleEndian(b[o..], v);
    public static void W64(Span<byte> b, int o, ulong v) => BinaryPrimitives.WriteUInt64LittleEndian(b[o..], v);

    public static bool Match(ReadOnlySpan<byte> b, int o, ReadOnlySpan<byte> sig)
        => o >= 0 && o + sig.Length <= b.Length && b.Slice(o, sig.Length).SequenceEqual(sig);

    public static string Ascii(ReadOnlySpan<byte> b, int o, int n)
        => Encoding.ASCII.GetString(b.Slice(o, n)).TrimEnd('\0', ' ');

    public static string Utf16(ReadOnlySpan<byte> b, int o, int chars)
    {
        var s = Encoding.Unicode.GetString(b.Slice(o, chars * 2));
        int z = s.IndexOf('\0');
        return z >= 0 ? s[..z] : s;
    }

    public static bool IsZero(ReadOnlySpan<byte> b) => b.IndexOfAnyExcept((byte)0) < 0;

    public static DateTime? FileTime(long ft)
    {
        if (ft <= 0 || ft > 2650467743999999999L) return null; // até o ano 9999
        try { return DateTime.FromFileTimeUtc(ft); } catch { return null; }
    }

    public static DateTime? Dos(ushort date, ushort time, int tenths = 0)
    {
        int day = date & 31, month = (date >> 5) & 15, year = 1980 + (date >> 9);
        int sec = (time & 31) * 2, min = (time >> 5) & 63, hour = time >> 11;
        if (day == 0 || month == 0 || month > 12 || hour > 23 || min > 59 || sec > 59) return null;
        if (day > DateTime.DaysInMonth(year, month)) return null;
        return new DateTime(year, month, day, hour, min, sec, DateTimeKind.Local).AddMilliseconds(tenths * 10);
    }
}
