using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace LibertyX.Partition.Core.Platform.Windows;

[SupportedOSPlatform("windows")]
internal static unsafe class Native
{
    public const uint GENERIC_READ = 0x80000000, GENERIC_WRITE = 0x40000000;
    public const uint FILE_SHARE_READ = 1, FILE_SHARE_WRITE = 2, FILE_SHARE_DELETE = 4;
    public const uint OPEN_EXISTING = 3;
    public const uint FILE_FLAG_NO_BUFFERING = 0x20000000, FILE_FLAG_WRITE_THROUGH = 0x80000000;

    public const uint IOCTL_DISK_GET_DRIVE_GEOMETRY_EX = 0x000700A0;
    public const uint IOCTL_DISK_GET_LENGTH_INFO = 0x0007405C;
    public const uint IOCTL_DISK_UPDATE_PROPERTIES = 0x00070140;
    public const uint IOCTL_DISK_GET_DRIVE_LAYOUT_EX = 0x00070050;
    public const uint IOCTL_STORAGE_QUERY_PROPERTY = 0x002D1400;
    public const uint IOCTL_STORAGE_GET_DEVICE_NUMBER = 0x002D1080;
    public const uint IOCTL_STORAGE_PREDICT_FAILURE = 0x002D1100;
    public const uint IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS = 0x00560000;
    public const uint SMART_GET_VERSION = 0x00074080;
    public const uint SMART_RCV_DRIVE_DATA = 0x0007C088;
    public const uint FSCTL_LOCK_VOLUME = 0x00090018;
    public const uint FSCTL_UNLOCK_VOLUME = 0x0009001C;
    public const uint FSCTL_DISMOUNT_VOLUME = 0x00090020;
    public const uint FSCTL_ALLOW_EXTENDED_DASD_IO = 0x00090083;
    public const uint FSCTL_EXTEND_VOLUME = 0x000900F0;

    public const int ERROR_INSUFFICIENT_BUFFER = 122, ERROR_MORE_DATA = 234, ERROR_CRC = 23, ERROR_SECTOR_NOT_FOUND = 27,
                     ERROR_IO_DEVICE = 1117, ERROR_SEM_TIMEOUT = 121, ERROR_FILE_NOT_FOUND = 2, ERROR_ACCESS_DENIED = 5;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern SafeFileHandle CreateFileW(string name, uint access, uint share, IntPtr sa, uint disposition, uint flags, IntPtr template);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool DeviceIoControl(SafeFileHandle h, uint code, void* inBuf, int inSize, void* outBuf, int outSize, out int returned, IntPtr overlapped);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool ReadFile(SafeFileHandle h, byte* buf, int toRead, out int read, NativeOverlapped* ov);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool WriteFile(SafeFileHandle h, byte* buf, int toWrite, out int written, NativeOverlapped* ov);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool FlushFileBuffers(SafeFileHandle h);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr FindFirstVolumeW(char* name, int len);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool FindNextVolumeW(IntPtr h, char* name, int len);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool FindVolumeClose(IntPtr h);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool GetVolumePathNamesForVolumeNameW(string volume, char* names, int len, out int needed);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool GetVolumeInformationW(string root, char* label, int labelLen, out uint serial, out uint maxComp, out uint flags, char* fs, int fsLen);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool GetDiskFreeSpaceExW(string dir, out ulong freeAvail, out ulong total, out ulong totalFree);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern int QueryDosDeviceW(string? device, char* target, int max);

    [DllImport("shell32.dll")]
    public static extern bool IsUserAnAdmin();

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode, Pack = 1)]
    public struct SHFILEOPSTRUCT
    {
        public IntPtr hwnd;
        public uint wFunc;
        [MarshalAs(UnmanagedType.LPWStr)] public string pFrom;
        [MarshalAs(UnmanagedType.LPWStr)] public string? pTo;
        public ushort fFlags;
        public int fAnyOperationsAborted;
        public IntPtr hNameMappings;
        [MarshalAs(UnmanagedType.LPWStr)] public string? lpszProgressTitle;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    public static extern int SHFileOperationW(ref SHFILEOPSTRUCT op);

    public static SafeFileHandle Open(string path, uint access, uint flags = 0)
    {
        var h = CreateFileW(path, access, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, IntPtr.Zero, OPEN_EXISTING, flags, IntPtr.Zero);
        return h;
    }

    /// <summary>DeviceIoControl com buffer que cresce sozinho quando a resposta não cabe.</summary>
    public static byte[]? Ioctl(SafeFileHandle h, uint code, byte[]? input, int outSize = 4096)
    {
        for (int tries = 0; tries < 8; tries++)
        {
            var output = new byte[outSize];
            int ret;
            bool ok;
            fixed (byte* i = input)
            fixed (byte* o = output)
                ok = DeviceIoControl(h, code, i, input?.Length ?? 0, o, output.Length, out ret, IntPtr.Zero);
            if (ok) { Array.Resize(ref output, ret); return output; }
            int err = Marshal.GetLastWin32Error();
            if (err is ERROR_INSUFFICIENT_BUFFER or ERROR_MORE_DATA) { outSize *= 4; continue; }
            return null;
        }
        return null;
    }

    public static bool IoctlNoData(SafeFileHandle h, uint code) =>
        DeviceIoControl(h, code, null, 0, null, 0, out _, IntPtr.Zero);

    public static bool IoctlIn(SafeFileHandle h, uint code, byte[] input)
    {
        fixed (byte* i = input) return DeviceIoControl(h, code, i, input.Length, null, 0, out _, IntPtr.Zero);
    }

    public static string? AnsiAt(byte[] b, int offset)
    {
        if (offset <= 0 || offset >= b.Length) return null;
        int end = Array.IndexOf(b, (byte)0, offset);
        if (end < 0) end = b.Length;
        return Encoding.ASCII.GetString(b, offset, end - offset).Trim();
    }

    /// <summary>Manda para a Lixeira (desfazível) em vez de apagar.</summary>
    public static bool Recycle(IEnumerable<string> paths)
    {
        var op = new SHFILEOPSTRUCT
        {
            wFunc = 3, // FO_DELETE
            pFrom = string.Join('\0', paths) + "\0\0",
            fFlags = 0x0040 | 0x0010 | 0x0004 | 0x0400, // ALLOWUNDO | NOCONFIRMATION | SILENT | NOERRORUI
        };
        return SHFileOperationW(ref op) == 0 && op.fAnyOperationsAborted == 0;
    }
}
