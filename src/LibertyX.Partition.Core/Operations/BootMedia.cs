using LibertyX.Partition.Core.FileSystems;
using LibertyX.Partition.Core.IO;
using LibertyX.Partition.Core.Partitioning;
using LibertyX.Partition.Core.Platform;
using LibertyX.Partition.Core.Platform.Windows;
using LibertyX.Partition.Core.Tools;

namespace LibertyX.Partition.Core.Operations;

/// <summary>
/// Pendrive de boot a partir de ISO. Dois caminhos:
/// - ISO do Windows: tabela MBR + FAT32 (inicia em UEFI e em BIOS), arquivos copiados, e o
///   install.wim acima de 4 GB dividido em .swm pelo DISM — sem NTFS, sem driver extra.
/// - Qualquer outra (Linux, ferramentas): gravação crua setor a setor (modo DD), que preserva
///   o boot híbrido dessas imagens.
/// </summary>
public static class BootMedia
{
    public static bool IsHybrid(string iso)
    {
        using var f = File.OpenRead(iso);
        var b = new byte[512];
        return f.Read(b) == 512 && b[510] == 0x55 && b[511] == 0xAA;
    }

    public static void WriteRaw(string iso, DiskInfo usb, OpSink sink)
    {
        if (usb.IsSystem) throw new InvalidOperationException("Esse é o disco do Windows.");
        using var img = new ImageFileDevice(iso, false, usb.SectorSize);
        if (img.Length > usb.Size) throw new InvalidOperationException($"A ISO ({DiskOperations.Size(img.Length)}) não cabe no pendrive ({DiskOperations.Size(usb.Size)}).");
        using var dev = DiskSystem.OpenDisk(usb, true);
        using (DiskSystem.Native.LockVolumes(usb))
        {
            // zera o fim do disco antes: um GPT reserva antigo confunde o firmware
            var zero = new byte[1 << 20];
            dev.Write(dev.Length - zero.Length, zero);
            SectorCopier.Copy(img, dev, new CopyOptions(), new Progress<CopyProgress>(p =>
                sink.Progress((double)p.Done / p.Total, "Gravando a imagem", $"{DiskOperations.Size(p.Done)} de {DiskOperations.Size(p.Total)} · {DiskOperations.Size((long)p.BytesPerSecond)}/s")), sink.Token);
            DiskSystem.Native.Rescan(usb);
        }
        DiskSystem.Native.Rescan(usb);
        sink.Log("Imagem gravada. O pendrive pode aparecer \"ilegível\" no Windows — é normal para ISOs de Linux.");
    }

    public static async Task WriteWindows(string iso, DiskInfo usb, string label, OpSink sink)
    {
        if (usb.IsSystem) throw new InvalidOperationException("Esse é o disco do Windows.");
        sink.Progress(-1, "Montando a ISO");
        string? src = await WinCommands.MountIso(iso, sink.Token);
        if (src == null) throw new InvalidOperationException("O Windows não conseguiu montar a ISO.");
        try
        {
            if (!File.Exists(Path.Combine(src + "\\", "sources", "install.wim")) && !File.Exists(Path.Combine(src + "\\", "sources", "install.esd")))
                throw new InvalidOperationException("Esta ISO não parece ser de instalação do Windows (sem sources\\install.wim/esd). Use o modo DD.");
            long need = new DirectoryInfo(src + "\\").EnumerateFiles("*", SearchOption.AllDirectories).Sum(f => f.Length);
            if (need + (64L << 20) > usb.Size) throw new InvalidOperationException($"A instalação precisa de {DiskOperations.Size(need)}; o pendrive tem {DiskOperations.Size(usb.Size)}.");

            int ss = usb.SectorSize;
            long start = 1024 * 1024 / ss;
            long count = Math.Min(usb.Size / ss - start, uint.MaxValue - start);
            var t = new PartitionTable { Style = TableStyle.Mbr, SectorSize = ss, DiskSectors = usb.Size / ss };
            t.Entries.Add(new PartitionEntry { StartLba = start, SectorCount = count, MbrType = 0x0C, Active = true });
            sink.Log("Criando a tabela MBR e formatando em FAT32…");
            using (var dev = DiskSystem.OpenDisk(usb, true))
            using (DiskSystem.Native.LockVolumes(usb))
            {
                dev.Write(0, new byte[1 << 20]);
                Fat32Formatter.Format(new SubDevice(dev, start * ss, count * ss), label, 0, start, new Progress<double>(x => sink.Progress(x * 0.05, "Formatando FAT32")));
                PartitionCodec.Write(dev, t);
                DiskSystem.Native.Rescan(usb);
            }
            DiskSystem.Native.Rescan(usb);
            await Task.Delay(3000, sink.Token);
            var lr = await WinCommands.AssignTemporaryLetter(usb.Number, start * ss);
            string letter = lr.Output.Trim();
            if (!lr.Ok || letter.Length != 1) throw new InvalidOperationException("O Windows não montou o pendrive novo: " + lr.Message);
            string dst = letter + ":";

            string wim = Path.Combine(src + "\\", "sources", "install.wim");
            bool split = File.Exists(wim) && new FileInfo(wim).Length >= (4L << 30) - 1;
            sink.Log($"Copiando os arquivos da instalação para {dst}…");
            sink.Progress(-1, "Copiando arquivos");
            var r = split
                ? await WinCommands.RobocopyExcluding(src + "\\", dst + "\\", "install.wim", sink.Token, l => { if (l.Contains('%')) return; if (l.Trim().Length > 0) sink.Log(l.Trim()); })
                : await WinCommands.Robocopy(src + "\\", dst + "\\", sink.Token, l => { if (l.Trim().Length > 0) sink.Log(l.Trim()); });
            if (r.ExitCode >= 8) throw new InvalidOperationException($"A cópia falhou (robocopy {r.ExitCode}).");
            if (split)
            {
                sink.Log("install.wim passa de 4 GB (limite do FAT32): dividindo em partes .swm com o DISM…");
                sink.Progress(-1, "Dividindo install.wim");
                var d = await WinCommands.SplitWim(wim, Path.Combine(dst + "\\", "sources", "install.swm"), sink.Token, l => { if (l.Trim().Length > 0 && !l.Contains('%')) sink.Log(l.Trim()); });
                if (!d.Ok) throw new InvalidOperationException("O DISM não conseguiu dividir o install.wim: " + d.Message);
            }
            sink.Log("Pendrive de instalação pronto: inicia em computadores UEFI e BIOS.");
        }
        finally { await WinCommands.DismountIso(iso); }
    }
}
